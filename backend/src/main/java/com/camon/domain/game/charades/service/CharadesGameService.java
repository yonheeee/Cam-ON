package com.camon.domain.game.charades.service;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.dto.CharadesGuessResponse;
import com.camon.domain.game.charades.dto.CharadesWordResponse;
import com.camon.domain.game.charades.repository.CharadesRedisRepository;
import com.camon.domain.game.charades.ws.CharadesEventPublisher;
import com.camon.domain.game.charades.ws.payload.CharadesAnswerRevealedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesGameEndedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRankingEntry;
import com.camon.domain.game.charades.ws.payload.CharadesRoundInvalidatedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundScoredPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundStartedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundTimeoutPayload;
import com.camon.domain.game.charades.ws.payload.CharadesScoreEntry;
import com.camon.domain.game.charades.ws.payload.CharadesTurnStartedPayload;
import com.camon.domain.game.charades.ws.payload.ChatMessageReceivedPayload;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharadesGameService {

    static final int MIN_PLAYERS = 3;
    static final int MAX_PLAYERS = 4;
    static final int TOTAL_ROUNDS = 1;
    static final Duration TURN_DURATION = Duration.ofMinutes(1);

    private static final String MISSION_TYPE = "CHARADES";
    private static final String TURN_STARTED_EVENT = "charades:turn-started";
    private static final String ROUND_STARTED_EVENT = "charades:round-started";
    private static final String CHAT_MESSAGE_EVENT = "chat:message-received";
    private static final String ANSWER_REVEALED_EVENT = "charades:answer-revealed";
    private static final String ROUND_TIMEOUT_EVENT = "charades:round-timeout";
    private static final String ROUND_INVALIDATED_EVENT =
        "charades:round-invalidated";
    private static final String ROUND_SCORED_EVENT = "charades:round-scored";
    private static final String GAME_ENDED_EVENT = "charades:game-ended";

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final MissionTopicRepository missionTopicRepository;
    private final MissionRepository missionRepository;
    private final CharadesRedisRepository charadesRedis;
    private final CharadesAnswerMatcher answerMatcher;
    private final GameScoreService gameScoreService;
    private final GameEventPublisher gameEventPublisher;
    private final CharadesEventPublisher charadesEventPublisher;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> pendingTimeouts =
        new ConcurrentHashMap<>();

    public CharadesGameService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        MissionTopicRepository missionTopicRepository,
        MissionRepository missionRepository,
        CharadesRedisRepository charadesRedis,
        CharadesAnswerMatcher answerMatcher,
        GameScoreService gameScoreService,
        GameEventPublisher gameEventPublisher,
        CharadesEventPublisher charadesEventPublisher,
        TaskScheduler taskScheduler
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.missionTopicRepository = missionTopicRepository;
        this.missionRepository = missionRepository;
        this.charadesRedis = charadesRedis;
        this.answerMatcher = answerMatcher;
        this.gameScoreService = gameScoreService;
        this.gameEventPublisher = gameEventPublisher;
        this.charadesEventPublisher = charadesEventPublisher;
        this.taskScheduler = taskScheduler;
    }

    @Transactional
    public CharadesTurnStartedPayload startSession(
        UUID roomId,
        Long gameId,
        Long topicId
    ) {
        Room room = resolveRoom(roomId);
        List<Participant> participants = connectedParticipants(roomId);
        validatePlayerCount(participants.size());
        validateTopic(gameId, topicId);

        List<Mission> missions = findTopicMissions(gameId, topicId);
        int requiredMissionCount = participants.size();
        if (missions.size() < requiredMissionCount) {
            throw new BusinessException(ErrorCode.CHARADES_NOT_ENOUGH_MISSIONS);
        }

        List<UUID> presenterOrder = participants.stream()
            .map(Participant::participantId)
            .toList();
        int sessionSeq = room.currentSessionSeq();
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        charadesRedis.initialize(
            room.roomCode(),
            sessionSeq,
            TOTAL_ROUNDS,
            topicId,
            presenterOrder
        );

        gameEventPublisher.publishStarted(
            room.roomId(),
            gameId,
            sessionSeq,
            TOTAL_ROUNDS
        );
        return openTurn(
            room,
            sessionSeq,
            1,
            1,
            presenterOrder.getFirst(),
            true
        );
    }

    public Optional<CharadesTurnStartedPayload> startNextTurn(UUID roomId) {
        return startNextTurn(roomId, false);
    }

    private Optional<CharadesTurnStartedPayload> startNextTurn(
        UUID roomId,
        boolean restartCurrentRound
    ) {
        Room room = resolveRoom(roomId);
        int sessionSeq = room.currentSessionSeq();
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.CHARADES_SESSION_NOT_FOUND)
        );
        if (state.status() == CharadesTurnStatus.PLAYING) {
            throw new BusinessException(ErrorCode.CHARADES_TURN_STILL_PLAYING);
        }

        List<UUID> presenterOrder = charadesRedis.getPresenterOrder(
            room.roomCode(),
            sessionSeq
        );
        int round = state.currentRound();
        int turn = state.currentTurn();

        while (round <= state.totalRounds()) {
            turn++;
            if (turn > presenterOrder.size()) {
                round++;
                turn = 1;
            }
            if (round > state.totalRounds()) {
                saveCompletedRound(room, sessionSeq, state.currentRound());
                return Optional.empty();
            }

            UUID candidate = presenterOrder.get(turn - 1);
            if (isConnected(roomId, candidate)) {
                if (round > state.currentRound()) {
                    saveCompletedRound(room, sessionSeq, state.currentRound());
                }
                return Optional.of(
                    openTurn(
                        room,
                        sessionSeq,
                        round,
                        turn,
                        candidate,
                        restartCurrentRound
                            || round > state.currentRound()
                    )
                );
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public CharadesWordResponse getCurrentWord(
        UUID roomId,
        Long gameId,
        UUID participantId
    ) {
        Room room = resolveRoom(roomId);
        int sessionSeq = room.currentSessionSeq();
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.CHARADES_SESSION_NOT_FOUND)
        );

        requireCurrentGame(state, gameId);
        requirePlayingTurn(state);
        if (!participantId.equals(state.presenterId())) {
            throw new BusinessException(ErrorCode.CHARADES_NOT_PRESENTER);
        }
        requireNotExpired(state, Instant.now());
        Mission mission = findCurrentMission(state, gameId);

        return new CharadesWordResponse(
            state.currentRound(),
            state.currentTurn(),
            mission.getKeyword(),
            state.expiresAt()
        );
    }

    @Transactional
    public CharadesGuessResponse submitGuess(
        UUID roomId,
        Long gameId,
        Participant participant,
        CharadesGuessRequest request
    ) {
        Room room = resolveRoom(roomId);
        int sessionSeq = room.currentSessionSeq();
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.CHARADES_SESSION_NOT_FOUND)
        );

        requireCurrentGame(state, gameId);
        requirePlayingTurn(state);
        if (participant.participantId().equals(state.presenterId())) {
            throw new BusinessException(
                ErrorCode.CHARADES_PRESENTER_CANNOT_GUESS
            );
        }

        Instant submittedAt = Instant.now();
        requireNotExpired(state, submittedAt);
        Mission mission = findCurrentMission(state, gameId);
        boolean matches = answerMatcher.matches(
            request.text(),
            mission.getKeyword()
        );
        boolean correct = matches && charadesRedis.claimCorrectAnswer(
            room.roomCode(),
            sessionSeq,
            participant.participantId(),
            submittedAt
        );

        charadesEventPublisher.publish(
            room.roomId(),
            CHAT_MESSAGE_EVENT,
            new ChatMessageReceivedPayload(
                state.currentRound(),
                state.currentTurn(),
                participant.participantId(),
                participant.nickname(),
                request.text(),
                submittedAt
            )
        );
        if (correct) {
            cancelPendingTimeout(room.roomCode(), sessionSeq);
            charadesEventPublisher.publish(
                room.roomId(),
                ANSWER_REVEALED_EVENT,
                new CharadesAnswerRevealedPayload(
                    state.currentRound(),
                    state.currentTurn(),
                    state.presenterId(),
                    participant.participantId(),
                    submittedAt
                )
            );
            advanceAfterTerminalTurn(room);
        }

        return new CharadesGuessResponse(
            state.currentRound(),
            state.currentTurn(),
            correct
        );
    }

    private CharadesTurnStartedPayload openTurn(
        Room room,
        int sessionSeq,
        int round,
        int turn,
        UUID presenterId,
        boolean announceRoundStarted
    ) {
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.CHARADES_SESSION_NOT_FOUND)
        );
        Mission mission = selectUnusedMission(
            state.topicId(),
            charadesRedis.getUsedMissionIds(room.roomCode(), sessionSeq)
        );
        Instant expiresAt = Instant.ofEpochMilli(
            Instant.now().plus(TURN_DURATION).toEpochMilli()
        );
        boolean opened = charadesRedis.openTurn(
            room.roomCode(),
            sessionSeq,
            round,
            turn,
            presenterId,
            mission.getMissionId(),
            expiresAt
        );
        if (!opened) {
            throw new BusinessException(ErrorCode.CHARADES_SESSION_NOT_FOUND);
        }

        CharadesTurnStartedPayload payload = new CharadesTurnStartedPayload(
            round,
            turn,
            state.totalTurnsInRound(),
            presenterId,
            expiresAt
        );
        if (announceRoundStarted) {
            charadesEventPublisher.publish(
                room.roomId(),
                ROUND_STARTED_EVENT,
                new CharadesRoundStartedPayload(
                    round,
                    state.totalRounds(),
                    state.totalTurnsInRound()
                )
            );
        }
        charadesEventPublisher.publish(
            room.roomId(),
            TURN_STARTED_EVENT,
            payload
        );
        scheduleTimeout(
            room,
            sessionSeq,
            round,
            turn,
            expiresAt
        );
        return payload;
    }

    public void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        String reason
    ) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return;
        }
        int sessionSeq = room.currentSessionSeq();
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElse(null);
        if (state == null || state.status() == CharadesTurnStatus.FINISHED) {
            return;
        }

        long connectedPlayerCount = participantRepository.findAll(roomId)
            .stream()
            .filter(participant ->
                participant.connectionStatus() == ConnectionStatus.CONNECTED
            )
            .count();
        if (connectedPlayerCount <= 1) {
            finishGame(room);
            return;
        }
        if (state.status() != CharadesTurnStatus.PLAYING
            || !participantId.equals(state.presenterId())) {
            return;
        }
        if (!charadesRedis.transitionStatus(
            room.roomCode(),
            sessionSeq,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.INVALIDATED
        )) {
            return;
        }

        cancelPendingTimeout(room.roomCode(), sessionSeq);
        charadesEventPublisher.publish(
            room.roomId(),
            ROUND_INVALIDATED_EVENT,
            new CharadesRoundInvalidatedPayload(
                state.currentRound(),
                state.currentTurn(),
                participantId,
                reason
            )
        );
        advanceAfterTerminalTurn(room, true);
    }

    private void scheduleTimeout(
        Room room,
        int sessionSeq,
        int round,
        int turn,
        Instant expiresAt
    ) {
        String key = timerKey(room.roomCode(), sessionSeq);
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> handleTimeout(
                room,
                sessionSeq,
                round,
                turn,
                expiresAt
            ),
            expiresAt
        );
        pendingTimeouts.compute(key, (ignored, current) -> {
            if (current != null) {
                current.cancel(false);
            }
            return future;
        });
    }

    void handleTimeout(
        Room room,
        int sessionSeq,
        int round,
        int turn,
        Instant expiresAt
    ) {
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElse(null);
        if (!isScheduledTurn(state, round, turn, expiresAt)) {
            return;
        }
        pendingTimeouts.remove(timerKey(room.roomCode(), sessionSeq));

        Instant now = Instant.now();
        if (now.isBefore(expiresAt)) {
            scheduleTimeout(room, sessionSeq, round, turn, expiresAt);
            return;
        }
        if (!charadesRedis.transitionStatus(
            room.roomCode(),
            sessionSeq,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.TIMEOUT
        )) {
            return;
        }

        charadesEventPublisher.publish(
            room.roomId(),
            ROUND_TIMEOUT_EVENT,
            new CharadesRoundTimeoutPayload(round, turn)
        );
        advanceAfterTerminalTurn(room);
    }

    private void advanceAfterTerminalTurn(Room room) {
        advanceAfterTerminalTurn(room, false);
    }

    private void advanceAfterTerminalTurn(
        Room room,
        boolean restartCurrentRound
    ) {
        if (startNextTurn(
            room.roomId(),
            restartCurrentRound
        ).isEmpty()) {
            finishGame(room);
        }
    }

    private void finishGame(Room room) {
        int sessionSeq = room.currentSessionSeq();
        CharadesGameState state = charadesRedis.findState(
            room.roomCode(),
            sessionSeq
        ).orElse(null);
        if (state == null || state.status() == CharadesTurnStatus.FINISHED) {
            return;
        }
        if (!charadesRedis.transitionStatus(
            room.roomCode(),
            sessionSeq,
            state.status(),
            CharadesTurnStatus.FINISHED
        )) {
            return;
        }
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        charadesEventPublisher.publish(
            room.roomId(),
            GAME_ENDED_EVENT,
            new CharadesGameEndedPayload(
                state.totalRounds(),
                Instant.now(),
                buildFinalRanking(room, sessionSeq)
            )
        );
        charadesRedis.clear(room.roomCode(), sessionSeq);
    }

    private void saveCompletedRound(
        Room room,
        int sessionSeq,
        int round
    ) {
        List<UUID> participantOrder = charadesRedis.getPresenterOrder(
            room.roomCode(),
            sessionSeq
        );
        Map<UUID, Long> earnedScores = charadesRedis.getRoundScores(
            room.roomCode(),
            sessionSeq,
            round
        );
        LinkedHashMap<UUID, Long> roundScores = new LinkedHashMap<>();
        participantOrder.forEach(participantId ->
            roundScores.put(
                participantId,
                earnedScores.getOrDefault(participantId, 0L)
            )
        );

        SaveRoundResult result = gameScoreService.saveRoundScores(
            room.roomId(),
            sessionSeq,
            round,
            roundScores
        );
        if (result == SaveRoundResult.ALREADY_SAVED) {
            return;
        }
        if (result != SaveRoundResult.SUCCESS) {
            throw new IllegalStateException(
                "Failed to save charades round score: " + result
            );
        }

        Map<UUID, Long> totals = gameScoreService.getSessionTotals(
            room.roomId(),
            sessionSeq
        );
        charadesEventPublisher.publish(
            room.roomId(),
            ROUND_SCORED_EVENT,
            new CharadesRoundScoredPayload(
                round,
                buildRoundScoreEntries(participantOrder, roundScores, totals)
            )
        );
    }

    private List<CharadesScoreEntry> buildRoundScoreEntries(
        List<UUID> participantOrder,
        Map<UUID, Long> roundScores,
        Map<UUID, Long> totals
    ) {
        Map<UUID, Integer> ranks = calculateRanks(participantOrder, totals);
        return participantOrder.stream()
            .map(participantId -> new CharadesScoreEntry(
                participantId,
                roundScores.getOrDefault(participantId, 0L),
                totals.getOrDefault(participantId, 0L),
                ranks.get(participantId)
            ))
            .sorted(
                Comparator.comparingInt(CharadesScoreEntry::rank)
                    .thenComparing(entry -> participantOrder.indexOf(
                        entry.participantId()
                    ))
            )
            .toList();
    }

    private List<CharadesRankingEntry> buildFinalRanking(
        Room room,
        int sessionSeq
    ) {
        List<UUID> participantOrder = charadesRedis.getPresenterOrder(
            room.roomCode(),
            sessionSeq
        );
        Map<UUID, Long> totals = gameScoreService.getSessionTotals(
            room.roomId(),
            sessionSeq
        );
        Map<UUID, Integer> ranks = calculateRanks(participantOrder, totals);
        return participantOrder.stream()
            .map(participantId -> new CharadesRankingEntry(
                participantId,
                totals.getOrDefault(participantId, 0L),
                ranks.get(participantId)
            ))
            .sorted(
                Comparator.comparingInt(CharadesRankingEntry::rank)
                    .thenComparing(entry -> participantOrder.indexOf(
                        entry.participantId()
                    ))
            )
            .toList();
    }

    private static Map<UUID, Integer> calculateRanks(
        List<UUID> participantOrder,
        Map<UUID, Long> totals
    ) {
        List<UUID> sorted = new ArrayList<>(participantOrder);
        sorted.sort(
            Comparator.comparingLong(
                (UUID participantId) ->
                    totals.getOrDefault(participantId, 0L)
            ).reversed()
        );

        LinkedHashMap<UUID, Integer> ranks = new LinkedHashMap<>();
        Long previousScore = null;
        int previousRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            UUID participantId = sorted.get(index);
            long score = totals.getOrDefault(participantId, 0L);
            int rank = previousScore != null && previousScore == score
                ? previousRank
                : index + 1;
            ranks.put(participantId, rank);
            previousScore = score;
            previousRank = rank;
        }
        return Map.copyOf(ranks);
    }

    private static boolean isScheduledTurn(
        CharadesGameState state,
        int round,
        int turn,
        Instant expiresAt
    ) {
        return state != null
            && state.status() == CharadesTurnStatus.PLAYING
            && state.currentRound() == round
            && state.currentTurn() == turn
            && state.expiresAt() != null
            && expiresAt.toEpochMilli() == state.expiresAt().toEpochMilli();
    }

    private void cancelPendingTimeout(String roomCode, int sessionSeq) {
        ScheduledFuture<?> future = pendingTimeouts.remove(
            timerKey(roomCode, sessionSeq)
        );
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String timerKey(String roomCode, int sessionSeq) {
        return roomCode + ":" + sessionSeq;
    }

    private Mission selectUnusedMission(Long topicId, Set<Long> usedMissionIds) {
        List<Mission> available = missionRepository
            .findAllByTopicTopicIdAndMissionTypeAndIsActiveTrue(
                topicId,
                MISSION_TYPE
            ).stream()
            .filter(mission -> !usedMissionIds.contains(mission.getMissionId()))
            .toList();
        if (available.isEmpty()) {
            throw new BusinessException(ErrorCode.CHARADES_NOT_ENOUGH_MISSIONS);
        }
        return available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }

    private void requireCurrentGame(
        CharadesGameState state,
        Long gameId
    ) {
        if (!missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(
                state.topicId(),
                gameId
            )) {
            throw new BusinessException(ErrorCode.GAME_NOT_CURRENT);
        }
    }

    private static void requirePlayingTurn(CharadesGameState state) {
        if (state.status() != CharadesTurnStatus.PLAYING) {
            throw new BusinessException(ErrorCode.CHARADES_TURN_NOT_PLAYING);
        }
    }

    private static void requireNotExpired(
        CharadesGameState state,
        Instant now
    ) {
        if (state.expiresAt() == null || !now.isBefore(state.expiresAt())) {
            throw new BusinessException(ErrorCode.CHARADES_TURN_EXPIRED);
        }
    }

    private Mission findCurrentMission(
        CharadesGameState state,
        Long gameId
    ) {
        if (state.missionId() == null) {
            throw new BusinessException(ErrorCode.CHARADES_WORD_NOT_FOUND);
        }
        Mission mission = missionRepository
            .findByMissionIdAndGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                state.missionId(),
                gameId,
                state.topicId(),
                MISSION_TYPE
            )
            .orElseThrow(() ->
                new BusinessException(ErrorCode.CHARADES_WORD_NOT_FOUND)
            );
        if (mission.getKeyword() == null || mission.getKeyword().isBlank()) {
            throw new BusinessException(ErrorCode.CHARADES_WORD_NOT_FOUND);
        }
        return mission;
    }

    private List<Mission> findTopicMissions(Long gameId, Long topicId) {
        return missionRepository
            .findAllByGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                gameId,
                topicId,
                MISSION_TYPE
            );
    }

    private List<Participant> connectedParticipants(UUID roomId) {
        return participantRepository.findAll(roomId).stream()
            .filter(participant ->
                participant.connectionStatus() == ConnectionStatus.CONNECTED
            )
            .toList();
    }

    private boolean isConnected(UUID roomId, UUID participantId) {
        return participantRepository.findById(roomId, participantId)
            .map(Participant::connectionStatus)
            .filter(ConnectionStatus.CONNECTED::equals)
            .isPresent();
    }

    private void validateTopic(Long gameId, Long topicId) {
        if (gameId == null || topicId == null
            || !missionTopicRepository
                .existsByTopicIdAndGameGameIdAndIsActiveTrue(topicId, gameId)) {
            throw new BusinessException(ErrorCode.CHARADES_TOPIC_NOT_FOUND);
        }
    }

    private static void validatePlayerCount(int playerCount) {
        if (playerCount < MIN_PLAYERS) {
            throw new BusinessException(ErrorCode.CHARADES_NOT_ENOUGH_PLAYERS);
        }
        if (playerCount > MAX_PLAYERS) {
            throw new BusinessException(ErrorCode.CHARADES_TOO_MANY_PLAYERS);
        }
    }

    private Room resolveRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }
}
