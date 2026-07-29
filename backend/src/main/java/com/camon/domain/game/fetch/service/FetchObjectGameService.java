package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.game.fetch.ws.FetchObjectEventPublisher;
import com.camon.domain.game.fetch.ws.payload.FetchGameEndedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundEndedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundStartedPayload;
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

        fetchEventPublisher.publish(
            room.roomId(),
            "round:end",
            new FetchRoundEndedPayload(
                round,
                totalRounds,
                endedAt.toEpochMilli()
            )
        );
        if (round >= totalRounds) {
            finishGame(room, sessionSeq);
            return;
        }
        startRound(room, sessionSeq, round + 1, totalRounds);
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
        List<Map.Entry<UUID, Long>> sorted = participants.stream()
            .map(participantId -> Map.entry(
                participantId,
                totals.getOrDefault(participantId, 0L)
            ))
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
