package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import com.camon.domain.game.fetch.dto.FetchObjectStateResponse;
import com.camon.domain.game.fetch.dto.FetchObjectSuccessEntry;
import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.repository.FetchObjectRoundState;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.game.fetch.repository.FetchObjectSubmissionRecord;
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
    // 첫 정답 이후 나머지에게 주는 마지막 제출 기회 — 이 시간이 지나면 라운드를 조기 마감한다.
    // (첫 정답 즉시 종료로 하면 "빨리 가져온 순서대로 1~4위" 경쟁이 사라져 그레이스를 둔다)
    static final Duration FIRST_SUBMISSION_GRACE = Duration.ofSeconds(5);
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
        Instant receivedAt = clock.instant();
        FetchSubmissionClaimResult result = fetchRedis.claimSubmission(
            room.roomCode(),
            sessionSeq,
            request.round(),
            participantId,
            receivedAt
        );
        validateSubmission(result.status());

        // 첫 정답이 나오면 라운드 마감을 그레이스(FIRST_SUBMISSION_GRACE)로 앞당긴다 —
        // 다른 사람이 물건을 못 찾으면 먼저 맞춘 사람이 남은 시간을 통째로 기다리던 문제의 해결.
        // 나머지 참가자는 그레이스 동안 마지막 제출 기회를 가진다(순위 경쟁 유지).
        Long shortenedDeadlineAt = null;
        if (result.rank() == 1 && !result.allParticipantsSubmitted()) {
            Instant currentDeadline = Instant.ofEpochMilli(result.deadlineAt());
            Instant graceDeadline = receivedAt.plus(FIRST_SUBMISSION_GRACE);
            if (graceDeadline.isBefore(currentDeadline)) {
                fetchRedis.shortenRoundDeadline(
                    room.roomCode(),
                    sessionSeq,
                    request.round(),
                    graceDeadline
                );
                rescheduleRoundTimeout(room, sessionSeq, request.round(), graceDeadline);
                shortenedDeadlineAt = graceDeadline.toEpochMilli();
            }
        }

        long score = fetchRedis.scoreForRank(result.rank());
        fetchEventPublisher.publish(
            room.roomId(),
            "round:success",
            new FetchRoundSuccessPayload(
                request.round(),
                participantId,
                result.rank(),
                score,
                receivedAt.toEpochMilli(),
                shortenedDeadlineAt
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

    @Transactional(readOnly = true)
    public FetchObjectStateResponse getState(Room room) {
        int sessionSeq = room.currentSessionSeq();
        FetchObjectRoundState state = fetchRedis.findCurrentRoundState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND)
        );
        ensureRoundTimeoutScheduled(room, sessionSeq, state);

        List<FetchObjectSuccessEntry> successes = state.submissions().stream()
            .map(submission -> new FetchObjectSuccessEntry(
                submission.participantId(),
                submission.rank(),
                fetchRedis.scoreForRank(submission.rank()),
                submission.submittedAt().toEpochMilli()
            ))
            .toList();

        Map<UUID, Long> savedTotals = gameScoreService.getSessionTotals(
            room.roomId(),
            sessionSeq
        );
        LinkedHashMap<UUID, Long> currentTotals = new LinkedHashMap<>();
        fetchRedis.getParticipants(room.roomCode(), sessionSeq).stream()
            .sorted()
            .forEach(participantId ->
                currentTotals.put(
                    participantId,
                    savedTotals.getOrDefault(participantId, 0L)
                )
            );
        // 진행 중 라운드의 점수는 round:end에서만 공통 점수 저장소에 기록된다. 상태 조회는
        // 그 전에도 현재 화면을 복구해야 하므로 Redis 제출 순위의 임시 점수를 합쳐 내려준다.
        if ("PLAYING".equals(state.status())) {
            for (FetchObjectSubmissionRecord submission : state.submissions()) {
                currentTotals.computeIfPresent(
                    submission.participantId(),
                    (ignored, total) ->
                        total + fetchRedis.scoreForRank(submission.rank())
                );
            }
        }

        return new FetchObjectStateResponse(
            state.round(),
            state.totalRounds(),
            state.target(),
            state.startedAt().toEpochMilli(),
            state.deadlineAt().toEpochMilli(),
            state.status(),
            successes,
            buildScoreEntries(currentTotals)
        );
    }

    private void ensureRoundTimeoutScheduled(
        Room room,
        int sessionSeq,
        FetchObjectRoundState state
    ) {
        if (!"PLAYING".equals(state.status())) {
            return;
        }
        // 라운드 마감 예약은 JVM 메모리에 있으므로 서버 재시작 시 사라진다. 프론트가 연결할 때
        // 호출하는 /state에서 Redis deadline을 읽어 예약을 복원한다. 이미 예약이 있으면
        // computeIfAbsent가 유지하고, 과거 deadline이면 TaskScheduler가 즉시 실행한다.
        pendingTimeouts.computeIfAbsent(
            timerKey(room.roomCode(), sessionSeq),
            ignored -> taskScheduler.schedule(
                () -> handleRoundTimeout(
                    room,
                    sessionSeq,
                    state.round(),
                    state.totalRounds(),
                    state.deadlineAt()
                ),
                state.deadlineAt()
            )
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

    // 첫 정답으로 마감이 앞당겨졌을 때 기존 타임아웃 타이머를 새 마감으로 교체한다.
    // 옛 타이머가 원래 마감에 살아남아 있어도 expectedDeadlineAt(원래 값) ≠ 저장된 deadline_at
    // (단축 값)이라 마감 CAS에서 무해하게 탈락하지만, 확실히 취소하고 새로 건다.
    private void rescheduleRoundTimeout(
        Room room,
        int sessionSeq,
        int round,
        Instant newDeadlineAt
    ) {
        Integer totalRounds = fetchRedis.getTotalRounds(room.roomCode(), sessionSeq);
        if (totalRounds == null) {
            return;
        }
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> handleRoundTimeout(room, sessionSeq, round, totalRounds, newDeadlineAt),
            newDeadlineAt
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
