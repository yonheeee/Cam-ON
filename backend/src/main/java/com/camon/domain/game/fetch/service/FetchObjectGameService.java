package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.demo.DemoScenario;
import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import com.camon.domain.game.fetch.dto.FetchObjectStateResponse;
import com.camon.domain.game.fetch.dto.FetchObjectSuccessEntry;
import com.camon.domain.game.fetch.dto.FetchSkipVoteResponse;
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
import com.camon.domain.game.fetch.ws.payload.FetchSkipVotePayload;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class FetchObjectGameService {

    static final Duration COUNTDOWN_DURATION = Duration.ofSeconds(3);
    // 20초 → 40초 (2026-08-04 플레이테스트 피드백): 물건 찾으러 자리를 뜨는 게임이라
    // 20초는 방 반대편 물건이 사실상 불가능했다. "아무도 못 찾는 라운드가 길어지는" 부작용은
    // 그레이스 단축 + (예정) 스킵 투표가 상한을 잡는다. 프론트 폴백(fetchGame.ts의
    // ROUND_DURATION_MS)과 함께 바꿔야 한다 — 스냅샷 복구 타이머가 어긋난다.
    static final Duration PLAY_DURATION = Duration.ofSeconds(40);
    // 첫 정답 이후 나머지에게 주는 마지막 제출 기회 — 이 시간이 지나면 라운드를 조기 마감한다.
    // (첫 정답 즉시 종료로 하면 "빨리 가져온 순서대로 1~4위" 경쟁이 사라져 그레이스를 둔다)
    // 5초 → 10초: 기본 시간이 40초로 늘며 "멀리 있는 물건"이 정상 플레이가 됐는데,
    // 5초는 찾고도 못 돌아오는 억울함이 있었다. 성공 후 지루함의 상한이기도 하다.
    static final Duration FIRST_SUBMISSION_GRACE = Duration.ofSeconds(10);
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

        // 시연 모드: 제시어 3개로 줄이고 무엇이 나올지도 고정한다(발표자가 물건을 미리 챙겨야 한다).
        // 라운드 수 검증(라운드 ≥ 인원)은 건너뛴다 — 4인 시연에서 3라운드는 그 규칙에 걸리는데,
        // 여기서 예외가 나면 CourseRunner가 이 게임을 통째로 건너뛰어 시연이 사라진다.
        boolean demo = room.demoMode();
        int rounds = demo ? DemoScenario.FETCH_TOTAL_ROUNDS : totalRounds;
        if (!demo) {
            validateRoundCount(rounds, connectedParticipants.size());
        }

        List<Long> missionOrder = demo
            ? demoMissionOrder(gameId, rounds)
            : selectMissionOrder(gameId, rounds);
        int sessionSeq = room.currentSessionSeq();
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        fetchRedis.initialize(
            room.roomCode(),
            sessionSeq,
            gameId,
            rounds,
            connectedParticipants.stream()
                .map(Participant::participantId)
                .toList(),
            missionOrder
        );

        gameEventPublisher.publishStarted(
            room.roomId(),
            gameId,
            sessionSeq,
            rounds
        );
        startRound(room, sessionSeq, 1, rounds);
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
                Instant.ofEpochMilli(result.deadlineAt()),
                false
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

        Set<UUID> connectedParticipants = fetchRedis.getParticipants(
            room.roomCode(),
            sessionSeq
        );
        List<UUID> skipVotes = fetchRedis.getSkipVotes(
                room.roomCode(),
                sessionSeq,
                state.round()
            ).stream()
            .filter(connectedParticipants::contains)
            .sorted()
            .toList();

        return new FetchObjectStateResponse(
            state.round(),
            state.totalRounds(),
            state.target(),
            state.startedAt().toEpochMilli(),
            state.deadlineAt().toEpochMilli(),
            state.status(),
            successes,
            buildScoreEntries(currentTotals),
            skipVotes
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
            expectedDeadlineAt,
            false
        );
    }

    /**
     * "주변에 물건이 없으면 타임아웃을 기다릴 수밖에 없다"는 피드백의 해결.
     * 첫 성공 전에만 유효하다 — 성공자가 나오면 그레이스(10초)가 라운드를 곧 닫으므로
     * 스킵의 역할이 없고, 남은 사람은 순위 경쟁 중이라 투표할 이유도 없다.
     * 가결 = 접속 참가자 전원 투표. 판정은 투표 set ∩ 참가자 set으로 계산해
     * 투표 후 떠난 사람의 표가 남아 있어도 무시된다.
     */
    public FetchSkipVoteResponse voteSkip(Room room, UUID participantId) {
        int sessionSeq = room.currentSessionSeq();
        FetchObjectRoundState state = fetchRedis.findCurrentRoundState(
            room.roomCode(),
            sessionSeq
        ).orElseThrow(() ->
            new BusinessException(ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND)
        );
        if (!"PLAYING".equals(state.status())) {
            throw new BusinessException(ErrorCode.FETCH_OBJECT_ROUND_CLOSED);
        }
        if (!state.submissions().isEmpty()) {
            throw new BusinessException(ErrorCode.FETCH_OBJECT_SKIP_UNAVAILABLE);
        }
        Set<UUID> participants = fetchRedis.getParticipants(
            room.roomCode(),
            sessionSeq
        );
        if (!participants.contains(participantId)) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_PARTICIPANT_NOT_FOUND
            );
        }

        fetchRedis.addSkipVote(
            room.roomCode(),
            sessionSeq,
            state.round(),
            participantId
        );
        int votes = countValidSkipVotes(
            room.roomCode(),
            sessionSeq,
            state.round(),
            participants
        );
        int required = participants.size();
        fetchEventPublisher.publish(
            room.roomId(),
            "round:skip-voted",
            new FetchSkipVotePayload(
                state.round(),
                participantId,
                votes,
                required
            )
        );
        // 마지막 두 명이 동시에 투표해 둘 다 여기 들어와도 completeRound의
        // closeRoundIfPlaying CAS가 한 번만 통과시킨다.
        if (votes >= required) {
            completeRound(
                room,
                sessionSeq,
                state.round(),
                state.totalRounds(),
                state.deadlineAt(),
                true
            );
        }
        return new FetchSkipVoteResponse(state.round(), votes, required);
    }

    private int countValidSkipVotes(
        String roomCode,
        int sessionSeq,
        int round,
        Set<UUID> participants
    ) {
        return (int) fetchRedis.getSkipVotes(roomCode, sessionSeq, round)
            .stream()
            .filter(participants::contains)
            .count();
    }

    private void completeRound(
        Room room,
        int sessionSeq,
        int round,
        int totalRounds,
        Instant expectedDeadlineAt,
        boolean skipped
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
                buildScoreEntries(roundScores),
                skipped
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

    /**
     * 진행 중에 참가자가 방을 떠났다. 물건 가져오기는 라운드가 시간 기반이라 닌자처럼 판이
     * 멈추지는 않지만, 떠난 사람이 참가자 집합에 남아 있으면 남은 사람이 전원 제출해도
     * 라운드가 조기에 닫히지 않고 매 라운드 제한시간을 다 태운다.
     */
    public void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        int connectedCount
    ) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return;
        }
        int sessionSeq = room.currentSessionSeq();
        // 물건 가져오기 세션이 열려 있지 않으면 내 차례가 아니다(다른 게임이 진행 중이거나
        // 이미 끝났다) — GameParticipantLeaveHandler 계약대로 조용히 빠진다.
        if (fetchRedis.findCurrentRoundState(room.roomCode(), sessionSeq).isEmpty()
            || fetchRedis.isSessionEnded(room.roomCode(), sessionSeq)) {
            return;
        }

        fetchRedis.removeParticipant(room.roomCode(), sessionSeq, participantId);
        // 혼자 남으면 더 겨룰 상대가 없다 — 남은 라운드를 다 돌리지 않고 여기서 끝낸다.
        if (connectedCount <= 1) {
            finishGame(room, sessionSeq);
            return;
        }
        completeRoundIfSkipVotePassed(room, sessionSeq);
    }

    // 스킵에 아직 안 누른 마지막 한 명이 떠나면, 남은 전원의 표가 이미 모여 있는데
    // 아무 이벤트도 안 일어나 라운드가 제한시간을 다 태운다 — 퇴장은 사실상 기권이므로
    // 남은 인원 기준으로 가결을 재판정한다.
    private void completeRoundIfSkipVotePassed(Room room, int sessionSeq) {
        FetchObjectRoundState state = fetchRedis.findCurrentRoundState(
            room.roomCode(),
            sessionSeq
        ).orElse(null);
        if (state == null
            || !"PLAYING".equals(state.status())
            || !state.submissions().isEmpty()) {
            return;
        }
        Set<UUID> participants = fetchRedis.getParticipants(
            room.roomCode(),
            sessionSeq
        );
        if (participants.isEmpty()) {
            return;
        }
        int votes = countValidSkipVotes(
            room.roomCode(),
            sessionSeq,
            state.round(),
            participants
        );
        if (votes >= participants.size()) {
            completeRound(
                room,
                sessionSeq,
                state.round(),
                state.totalRounds(),
                state.deadlineAt(),
                true
            );
        }
    }

    private void finishGame(Room room, int sessionSeq) {
        cancelPendingTimeout(room.roomCode(), sessionSeq);
        fetchRedis.markSessionEnded(room.roomCode(), sessionSeq);
        List<FetchScoreEntry> finalScores = buildFinalScores(room, sessionSeq);
        SaveRoundResult courseResult = gameScoreService.saveCourseRanking(
            room.roomId(),
            sessionSeq,
            finalScores.stream().collect(Collectors.toMap(
                FetchScoreEntry::participantId,
                FetchScoreEntry::rank,
                (left, right) -> left,
                LinkedHashMap::new
            ))
        );
        if (courseResult != SaveRoundResult.SUCCESS
            && courseResult != SaveRoundResult.ALREADY_SAVED) {
            throw new IllegalStateException(
                "Failed to save fetch course score: " + courseResult
            );
        }
        fetchEventPublisher.publish(
            room.roomId(),
            "game:end",
            new FetchGameEndedPayload(
                finalScores,
                gameScoreService.getCourseTotals(room.roomId())
            )
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

    // 시연용 제시어 순서. DemoScenario에 적힌 물건을 그 순서대로 낸다 — 하나라도 DB에 없으면
    // 라운드 수가 모자라게 되므로 통째로 포기하고 평소의 랜덤 선택으로 돌아간다.
    private List<Long> demoMissionOrder(Long gameId, int totalRounds) {
        Map<String, Mission> missionByKeyword = missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                gameId,
                FetchObjectMissionCatalog.MISSION_TYPE
            )
            .stream()
            .collect(Collectors.toMap(
                Mission::getKeyword,
                mission -> mission,
                BinaryOperator.minBy(
                    Comparator.comparing(Mission::getMissionId)
                )
            ));
        List<Long> order = DemoScenario.FETCH_KEYWORDS.stream()
            .map(missionByKeyword::get)
            .filter(java.util.Objects::nonNull)
            .map(Mission::getMissionId)
            .limit(totalRounds)
            .toList();
        if (order.size() < totalRounds) {
            log.warn("[Fetch] 시연 모드 : 제시어 {} 중 DB에 있는 게 {}개뿐 — 랜덤 선택으로 진행",
                DemoScenario.FETCH_KEYWORDS, order.size());
            return selectMissionOrder(gameId, totalRounds);
        }
        log.info("[Fetch] 시연 모드 : 제시어를 {}로 고정 ({}라운드)",
            DemoScenario.FETCH_KEYWORDS, totalRounds);
        return order;
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
