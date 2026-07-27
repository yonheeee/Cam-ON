package com.camon.domain.game.charades.service;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.dto.CharadesGuessResponse;
import com.camon.domain.game.charades.dto.CharadesWordResponse;
import com.camon.domain.game.charades.repository.CharadesRedisRepository;
import com.camon.domain.game.charades.ws.CharadesEventPublisher;
import com.camon.domain.game.charades.ws.payload.CharadesAnswerRevealedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesTurnStartedPayload;
import com.camon.domain.game.charades.ws.payload.ChatMessageReceivedPayload;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharadesGameService {

    static final int MIN_PLAYERS = 3;
    static final int MAX_PLAYERS = 4;
    static final Set<Integer> ALLOWED_ROUND_COUNTS = Set.of(3, 5, 7, 9);
    static final Duration TURN_DURATION = Duration.ofMinutes(1);

    private static final String MISSION_TYPE = "CHARADES";
    private static final String TURN_STARTED_EVENT = "charades:turn-started";
    private static final String CHAT_MESSAGE_EVENT = "chat:message-received";
    private static final String ANSWER_REVEALED_EVENT = "charades:answer-revealed";

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final MissionTopicRepository missionTopicRepository;
    private final MissionRepository missionRepository;
    private final CharadesRedisRepository charadesRedis;
    private final CharadesAnswerMatcher answerMatcher;
    private final GameEventPublisher gameEventPublisher;
    private final CharadesEventPublisher charadesEventPublisher;

    public CharadesGameService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        MissionTopicRepository missionTopicRepository,
        MissionRepository missionRepository,
        CharadesRedisRepository charadesRedis,
        CharadesAnswerMatcher answerMatcher,
        GameEventPublisher gameEventPublisher,
        CharadesEventPublisher charadesEventPublisher
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.missionTopicRepository = missionTopicRepository;
        this.missionRepository = missionRepository;
        this.charadesRedis = charadesRedis;
        this.answerMatcher = answerMatcher;
        this.gameEventPublisher = gameEventPublisher;
        this.charadesEventPublisher = charadesEventPublisher;
    }

    @Transactional
    public CharadesTurnStartedPayload startSession(
        UUID roomId,
        Long gameId,
        Long topicId,
        int totalRounds
    ) {
        validateRoundCount(totalRounds);
        Room room = resolveRoom(roomId);
        List<Participant> participants = connectedParticipants(roomId);
        validatePlayerCount(participants.size());
        validateTopic(gameId, topicId);

        List<Mission> missions = findTopicMissions(gameId, topicId);
        int requiredMissionCount = Math.multiplyExact(
            participants.size(),
            totalRounds
        );
        if (missions.size() < requiredMissionCount) {
            throw new BusinessException(ErrorCode.CHARADES_NOT_ENOUGH_MISSIONS);
        }

        List<UUID> presenterOrder = participants.stream()
            .map(Participant::participantId)
            .toList();
        int sessionSeq = room.currentSessionSeq();
        charadesRedis.initialize(
            room.roomCode(),
            sessionSeq,
            totalRounds,
            topicId,
            presenterOrder
        );

        gameEventPublisher.publishStarted(
            room.roomId(),
            gameId,
            sessionSeq,
            totalRounds
        );
        return openTurn(room, sessionSeq, 1, 1, presenterOrder.getFirst());
    }

    public Optional<CharadesTurnStartedPayload> startNextTurn(UUID roomId) {
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
                return Optional.empty();
            }

            UUID candidate = presenterOrder.get(turn - 1);
            if (isConnected(roomId, candidate)) {
                return Optional.of(
                    openTurn(room, sessionSeq, round, turn, candidate)
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
        UUID presenterId
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
        Instant expiresAt = Instant.now().plus(TURN_DURATION);
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
        charadesEventPublisher.publish(
            room.roomId(),
            TURN_STARTED_EVENT,
            payload
        );
        return payload;
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

    private static void validateRoundCount(int totalRounds) {
        if (!ALLOWED_ROUND_COUNTS.contains(totalRounds)) {
            throw new BusinessException(ErrorCode.CHARADES_INVALID_ROUND_COUNT);
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
