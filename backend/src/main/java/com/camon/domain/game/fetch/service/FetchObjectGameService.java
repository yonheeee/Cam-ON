package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.game.fetch.repository.FetchSubmissionClaimResult;
import com.camon.domain.game.fetch.repository.FetchSubmissionStatus;
import com.camon.domain.game.fetch.ws.FetchObjectEventPublisher;
import com.camon.domain.game.fetch.ws.payload.FetchGameEndedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundEndedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundStartedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundSuccessPayload;
import com.camon.domain.game.fetch.ws.payload.FetchScoreEntry;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FetchObjectGameService {

    static final Duration COUNTDOWN_DURATION = Duration.ofSeconds(3);
    static final Duration PLAY_DURATION = Duration.ofSeconds(20);
    static final Duration ROUND_DURATION =
        COUNTDOWN_DURATION.plus(PLAY_DURATION);
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4;
    private static final int MAX_ROUNDS = 10;

    private final RoomRepository roomRepository;
    private final MissionRepository missionRepository;
    private final FetchObjectRedisRepository fetchRedis;
    private final GameEventPublisher gameEventPublisher;
    private final FetchObjectEventPublisher fetchEventPublisher;
    private final GameScoreService gameScoreService;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;
    private final Map<String, ScheduledFuture<?>> pendingTimeouts =
        new ConcurrentHashMap<>();

    public FetchObjectGameService(
        RoomRepository roomRepository,
        MissionRepository missionRepository,
        FetchObjectRedisRepository fetchRedis,
        GameEventPublisher gameEventPublisher,
        FetchObjectEventPublisher fetchEventPublisher,
        GameScoreService gameScoreService,
        TaskScheduler taskScheduler,
        ApplicationEventPublisher applicationEventPublisher,
        Clock jwtClock
    ) {
        this.roomRepository = roomRepository;
        this.missionRepository = missionRepository;
        this.fetchRedis = fetchRedis;
        this.gameEventPublisher = gameEventPublisher;
        this.fetchEventPublisher = fetchEventPublisher;
        this.gameScoreService = gameScoreService;
        this.taskScheduler = taskScheduler;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = jwtClock;
    }

    @Transactional(readOnly = true)
    public void startSession(
        UUID roomId,
        Long gameId,
        List<Participant> participants,
        int totalRounds
    ) {
        Room room = resolveRoom(roomId);
        List<Participant> connectedParticipants = participants.stream()
            .filter(participant ->
                participant.connectionStatus() == ConnectionStatus.CONNECTED
            )
            .toList();
        validatePlayerCount(connectedParticipants.size());
        validateRoundCount(totalRounds, connectedParticipants.size());

        List<Long> missionOrder = selectMissionOrder(gameId, totalRounds);
        int sessionSeq = room.currentSessionSeq();
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        fetchRedis.initialize(
            room.roomCode(),
            sessionSeq,
            gameId,
            totalRounds,
            connectedParticipants.stream()
                .map(Participant::participantId)
                .toList(),
            missionOrder
        );

        gameEventPublisher.publishStarted(
            room.roomId(),
            gameId,
            sessionSeq,
            totalRounds
        );
        startRound(room, sessionSeq, 1, totalRounds);
    }

    public FetchSubmissionResponse submit(
        Room room,
        UUID participantId,
        FetchSubmissionRequest request
    ) {
        int sessionSeq = room.currentSessionSeq();
        FetchSubmissionClaimResult result = fetchRedis.claimSubmission(
            room.roomCode(),
            sessionSeq,
            request.round(),
            participantId,
            clock.instant()
        );
        validateSubmission(result.status());

        long score = fetchRedis.scoreForRank(result.rank());
        fetchEventPublisher.publish(
            room.roomId(),
            "round:success",
            new FetchRoundSuccessPayload(
                participantId,
                result.rank(),
                score
            )
        );
        if (result.allParticipantsSubmitted()) {
            Integer totalRounds = fetchRedis.getTotalRounds(
                room.roomCode(),
                sessionSeq
            );
            if (totalRounds == null) {
                throw new BusinessException(
                    ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND
                );
            }
            completeRound(
                room,
                sessionSeq,
                request.round(),
                totalRounds,
                Instant.ofEpochMilli(result.deadlineAt())
            );
        }
        return new FetchSubmissionResponse(
            request.round(),
            participantId,
            result.rank(),
            score
        );
    }

    private void startRound(
        Room room,
        int sessionSeq,
        int round,
        int totalRounds
    ) {
        Long missionId = fetchRedis.getMissionIdAt(
            room.roomCode(),
            sessionSeq,
            round
        );
        Mission mission = missionId == null
            ? null
            : missionRepository.findById(missionId).orElse(null);
        if (mission == null
            || !FetchObjectMissionCatalog.MISSION_TYPE.equals(
                mission.getMissionType()
            )
            || !FetchObjectMissionCatalog.KEYWORDS.contains(
                mission.getKeyword()
            )) {
            throw new BusinessException(ErrorCode.FETCH_OBJECT_MISSION_NOT_FOUND);
        }

        Instant startedAt = clock.instant();
        Instant submissionOpensAt = startedAt.plus(COUNTDOWN_DURATION);
        Instant deadlineAt = startedAt.plus(ROUND_DURATION);
        fetchRedis.openRound(
            room.roomCode(),
            sessionSeq,
            round,
            mission.getMissionId(),
            mission.getKeyword(),
            startedAt,
            submissionOpensAt,
            deadlineAt
        );
        fetchEventPublisher.publish(
            room.roomId(),
            "round:start",
            new FetchRoundStartedPayload(
                round,
                totalRounds,
                mission.getKeyword(),
                startedAt.toEpochMilli()
            )
        );

        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> handleRoundTimeout(
                room,
                sessionSeq,
                round,
                totalRounds,
                deadlineAt
            ),
            deadlineAt
        );
        pendingTimeouts.put(timerKey(room.roomCode(), sessionSeq), future);
    }

    private void handleRoundTimeout(
        Room room,
        int sessionSeq,
        int round,
        int totalRounds,
        Instant expectedDeadlineAt
    ) {
        pendingTimeouts.remove(timerKey(room.roomCode(), sessionSeq));
        completeRound(
            room,
            sessionSeq,
            round,
            totalRounds,
            expectedDeadlineAt
        );
    }

    private void completeRound(
        Room room,
        int sessionSeq,
        int round,
        int totalRounds,
        Instant expectedDeadlineAt
    ) {
        Instant endedAt = clock.instant();
        if (!fetchRedis.closeRoundIfPlaying(
            room.roomCode(),
            sessionSeq,
            round,
            expectedDeadlineAt,
            endedAt
        )) {
            return;
        }

        cancelPendingTimeout(room.roomCode(), sessionSeq);
        Map<UUID, Long> roundScores = buildRoundScores(
            room.roomCode(),
            sessionSeq,
            round
        );
        SaveRoundResult saveResult = gameScoreService.saveRoundScores(
            room.roomId(),
            sessionSeq,
            round,
            roundScores
        );
        if (saveResult != SaveRoundResult.SUCCESS
            && saveResult != SaveRoundResult.ALREADY_SAVED) {
            throw new IllegalStateException(
                "Failed to save fetch round scores: " + saveResult
            );
        }
        fetchEventPublisher.publish(
            room.roomId(),
            "round:end",
            new FetchRoundEndedPayload(
                round,
                totalRounds,
                endedAt.toEpochMilli(),
                buildScoreEntries(roundScores)
            )
        );
        if (round >= totalRounds) {
            finishGame(room, sessionSeq);
            return;
        }
        startRound(room, sessionSeq, round + 1, totalRounds);
    }

    private Map<UUID, Long> buildRoundScores(
        String roomCode,
        int sessionSeq,
        int round
    ) {
        LinkedHashMap<UUID, Long> scores = new LinkedHashMap<>();
        fetchRedis.getParticipants(roomCode, sessionSeq).stream()
            .sorted()
            .forEach(participantId -> scores.put(participantId, 0L));
        List<UUID> submissionOrder = fetchRedis.getSubmissionOrder(
            roomCode,
            sessionSeq,
            round
        );
        for (int index = 0; index < submissionOrder.size(); index++) {
            scores.put(
                submissionOrder.get(index),
                fetchRedis.scoreForRank(index + 1)
            );
        }
        return Map.copyOf(scores);
    }

    private void finishGame(Room room, int sessionSeq) {
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        fetchRedis.markSessionEnded(room.roomCode(), sessionSeq);
        fetchEventPublisher.publish(
            room.roomId(),
            "game:end",
            new FetchGameEndedPayload(buildFinalScores(room, sessionSeq))
        );
        applicationEventPublisher.publishEvent(
            new GameSessionFinishedEvent(room.roomId(), sessionSeq)
        );
    }

    private List<FetchScoreEntry> buildFinalScores(
        Room room,
        int sessionSeq
    ) {
        Set<UUID> participants = fetchRedis.getParticipants(
            room.roomCode(),
            sessionSeq
        );
        Map<UUID, Long> totals = gameScoreService.getSessionTotals(
            room.roomId(),
            sessionSeq
        );
        Map<UUID, Long> participantTotals = participants.stream()
            .collect(Collectors.toMap(
                participantId -> participantId,
                participantId -> totals.getOrDefault(participantId, 0L)
            ));
        return buildScoreEntries(participantTotals);
    }

    private List<FetchScoreEntry> buildScoreEntries(
        Map<UUID, Long> scoreByParticipant
    ) {
        List<Map.Entry<UUID, Long>> sorted = scoreByParticipant.entrySet()
            .stream()
            .sorted(
                Comparator.<Map.Entry<UUID, Long>>comparingLong(
                    Map.Entry::getValue
                )
                    .reversed()
                    .thenComparing(entry -> entry.getKey().toString())
            )
            .toList();

        List<FetchScoreEntry> scores = new ArrayList<>(sorted.size());
        long previousScore = Long.MIN_VALUE;
        int previousRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<UUID, Long> entry = sorted.get(index);
            int rank = entry.getValue() == previousScore
                ? previousRank
                : index + 1;
            scores.add(new FetchScoreEntry(entry.getKey(), entry.getValue(), rank));
            previousScore = entry.getValue();
            previousRank = rank;
        }
        return List.copyOf(scores);
    }

    private static void validateSubmission(FetchSubmissionStatus status) {
        ErrorCode errorCode = switch (status) {
            case SUCCESS -> null;
            case SESSION_NOT_FOUND ->
                ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND;
            case ROUND_NOT_FOUND ->
                ErrorCode.FETCH_OBJECT_ROUND_NOT_FOUND;
            case STALE_ROUND -> ErrorCode.FETCH_OBJECT_STALE_ROUND;
            case ROUND_CLOSED -> ErrorCode.FETCH_OBJECT_ROUND_CLOSED;
            case COUNTDOWN_ACTIVE ->
                ErrorCode.FETCH_OBJECT_COUNTDOWN_ACTIVE;
            case ROUND_EXPIRED -> ErrorCode.FETCH_OBJECT_ROUND_EXPIRED;
            case PARTICIPANT_NOT_FOUND ->
                ErrorCode.FETCH_OBJECT_PARTICIPANT_NOT_FOUND;
            case ALREADY_SUBMITTED ->
                ErrorCode.FETCH_OBJECT_ALREADY_SUBMITTED;
        };
        if (errorCode != null) {
            throw new BusinessException(errorCode);
        }
    }

    private List<Long> selectMissionOrder(Long gameId, int totalRounds) {
        Map<String, Mission> missionByKeyword = missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                gameId,
                FetchObjectMissionCatalog.MISSION_TYPE
            )
            .stream()
            .filter(mission ->
                FetchObjectMissionCatalog.KEYWORDS.contains(
                    mission.getKeyword()
                )
            )
            .collect(Collectors.toMap(
                Mission::getKeyword,
                mission -> mission,
                BinaryOperator.minBy(
                    Comparator.comparing(Mission::getMissionId)
                ),
                LinkedHashMap::new
            ));
        List<Mission> pool = FetchObjectMissionCatalog.KEYWORDS.stream()
            .map(missionByKeyword::get)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));
        if (pool.size() < totalRounds) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_NOT_ENOUGH_MISSIONS
            );
        }
        Collections.shuffle(pool);
        return pool.subList(0, totalRounds).stream()
            .map(Mission::getMissionId)
            .toList();
    }

    private static void validatePlayerCount(int playerCount) {
        if (playerCount < MIN_PLAYERS) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_NOT_ENOUGH_PLAYERS
            );
        }
        if (playerCount > MAX_PLAYERS) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_TOO_MANY_PLAYERS
            );
        }
    }

    private static void validateRoundCount(
        int totalRounds,
        int playerCount
    ) {
        if (totalRounds < playerCount || totalRounds > MAX_ROUNDS) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_INVALID_ROUND_COUNT
            );
        }
    }

    private Room resolveRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private static String timerKey(String roomCode, int sessionSeq) {
        return roomCode + ":" + sessionSeq;
    }

    private void cancelPendingTimeout(String roomCode, int sessionSeq) {
        ScheduledFuture<?> future = pendingTimeouts.remove(
            timerKey(roomCode, sessionSeq)
        );
        if (future != null) {
            future.cancel(false);
        }
    }
}
