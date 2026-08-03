package com.camon.domain.course.service;

import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.domain.AnalyticsEventName;
import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.course.ws.CourseEventPublisher;
import com.camon.domain.course.ws.payload.CourseFinishedPayload;
import com.camon.domain.course.ws.payload.CourseIntermissionPayload;
import com.camon.domain.course.ws.payload.CourseScoreEntry;
import com.camon.domain.course.ws.payload.CourseSessionSkippedPayload;
import com.camon.domain.course.ws.payload.MemberReturnedPayload;
import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.service.GameCatalogService;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.service.GameSessionSpec;
import com.camon.domain.game.common.service.GameSessionStarter;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import static java.util.Objects.requireNonNullElse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// 대기방에서 확정된 코스를 실제로 굴리는 오케스트레이터.
//
// 코스 = 게임 여러 개의 줄. 한 게임이 끝나면(GameSessionFinishedEvent) 잠깐 결과를 보여준 뒤
// 다음 칸의 게임 세션을 연다. 마지막 칸까지 끝나면 코스 종합 결과를 발행하고 방을 끝낸다.
//
// 세션 번호(seq)는 코스의 위치(idx)와 1:1이다. 코스가 끝난 방은 FINISHED로 닫히고,
// 참가자가 각자 "방으로 돌아가기"(returnToLobby)를 누르면 대기방으로 돌아온다. 그중 가장 먼저
// 누른 한 명이 점수 키(session:{seq}:*, course:totals)를 전부 지우고 방을 WAITING으로
// 되돌린다 — 점수가 HINCRBY로 누적되므로 지우지 않고 seq를 재사용하면 이전 코스 점수가
// 그대로 얹힌다. 아직 안 돌아온 사람은 방에 남아 있는 채(inLobby=false) 대기방 타일에
// "게임 중"으로 표시되고, 전원이 돌아와 준비해야 다음 코스를 시작할 수 있다.
@Slf4j
@Service
public class CourseRunner {

    // 다음 게임 룰 설명 화면(course:intermission)이 떠 있는 시간. 코스 첫 게임 앞에서는 이
    // 구간만 있고, 게임 사이에서는 아래 세트 결과 구간이 끝난 뒤에 이어진다.
    // 게임 내부 인터미션(닌자 이펙트 5초 + 카운트다운 3초)과는 별개의 구간이다.
    private static final Duration SESSION_INTERMISSION = Duration.ofSeconds(8);

    // 게임 하나가 끝난 뒤 그 세트의 순위/점수를 읽는 시간. 이 구간이 지나면 룰 설명 구간이
    // 열린다 — 두 화면을 겹쳐 띄우면 룰 설명이 결과를 덮으므로 순서대로 준다.
    // 프론트의 세트 결과 카운트다운(VideoCallRoom.SET_RESULT_DURATION_MS)과 같은 값이어야 한다.
    private static final Duration SET_RESULT_DURATION = Duration.ofSeconds(8);

    // "코스는 시작됐지만 아직 첫 게임이 열리지 않았다"를 나타내는 seq. 첫 게임도 룰 설명을 보고
    // 들어가게 되면서, 게임이 하나도 안 열린 구간을 표현할 값이 필요해졌다. 게임 사이 인터미션이
    // "직전 seq"를 쓰는 것과 같은 자리에 들어가므로, 첫 게임 앞 인터미션과 게임 사이 인터미션이
    // 진행/스킵 경로를 그대로 공유한다(advance(0) → 1번 칸을 연다).
    private static final int BEFORE_FIRST_SESSION = 0;

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final CourseRepository courseRepository;
    private final CourseService courseService;
    private final GameCatalogService gameCatalogService;
    private final GameScoreService gameScoreService;
    private final CourseEventPublisher courseEventPublisher;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher applicationEventPublisher;
    // games.name → 그 게임의 세션을 여는 방법. 게임이 추가되면 빈이 하나 늘어날 뿐 이 클래스는
    // 바뀌지 않는다.
    private final Map<String, GameSessionStarter> startersByGameName;

    public CourseRunner(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        CourseRepository courseRepository,
        CourseService courseService,
        GameCatalogService gameCatalogService,
        GameScoreService gameScoreService,
        CourseEventPublisher courseEventPublisher,
        TaskScheduler taskScheduler,
        List<GameSessionStarter> sessionStarters,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.courseRepository = courseRepository;
        this.courseService = courseService;
        this.gameCatalogService = gameCatalogService;
        this.gameScoreService = gameScoreService;
        this.courseEventPublisher = courseEventPublisher;
        this.taskScheduler = taskScheduler;
        this.applicationEventPublisher = applicationEventPublisher;
        this.startersByGameName = sessionStarters.stream().collect(
            Collectors.toUnmodifiableMap(
                GameSessionStarter::gameName,
                Function.identity()
            )
        );
    }

    // 방장이 대기방에서 "게임 시작"을 누르는 지점.
    //
    // 첫 게임을 곧바로 열지 않고 룰 설명 인터미션을 먼저 준다 — 게임 사이와 똑같은 구간이다.
    // 예전엔 여기서 바로 openSession을 불러서, 첫 게임만 설명 없이 시작됐다. 지금은 seq를
    // BEFORE_FIRST_SESSION으로 두고 advance(0)를 예약하므로, 첫 게임 앞 인터미션이 게임 사이
    // 인터미션과 같은 진행/스킵 경로를 탄다(방장은 "바로 시작"으로 건너뛸 수 있다).
    public StartedSession startCourse(UUID roomId, UUID requesterId) {
        Room room = requireRoom(roomId);
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw new BusinessException(ErrorCode.ROOM_ALREADY_STARTED);
        }

        List<Participant> participants = participantRepository.findAll(roomId);
        // 이전 코스 결과 화면에 아직 남아 있는 사람이 있으면 시작하지 않는다 — 그대로 열면
        // 결과를 읽는 중에 게임 화면으로 끌려 들어간다. ready만 봐도 대개 걸리지만(복귀 전엔
        // 준비할 방법이 없다) 방장은 복귀 시 ready=true가 되므로 별도 검증이 필요하다.
        if (participants.stream().anyMatch(participant -> !participant.inLobby())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ALL_RETURNED);
        }
        // 방장은 방 생성 시 ready=true로 시작하므로(시작 버튼이 곧 준비 의사) 특별취급 없이
        // 전원이 ready인지만 본다.
        if (participants.isEmpty()
            || participants.stream().anyMatch(participant -> !participant.ready())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ALL_READY);
        }

        List<CourseItem> items = courseRepository.findAll(roomId, room.roomCode());
        // 인원 조건은 코스 저장 때가 아니라 여기서 본다 — 저장 시점엔 사람이 계속 드나든다.
        courseService.validatePlayable(items, participants.size());

        // 아직 게임을 열지 않는다 — 룰 설명 인터미션이 끝나야 1번 칸이 열린다.
        roomRepository.updateCurrentSessionSeq(roomId, BEFORE_FIRST_SESSION);
        // status를 먼저 PLAYING으로 올려, 인터미션·game:started가 나갈 시점엔 이미 PLAYING이
        // 되도록 한다(늦게 붙은 클라이언트가 방 status 조회로 게임 화면을 복구할 수 있게).
        roomRepository.updateStatus(roomId, RoomStatus.PLAYING);
        // 게임 중엔 아무도 대기방에 없다. 코스가 끝나면 각자 "방으로 돌아가기"로 다시 true가 된다.
        participantRepository.updateAllInLobby(roomId, false);

        Instant resumesAt = Instant.now().plus(SESSION_INTERMISSION);
        CourseIntermissionPayload intermission =
            buildIntermission(room, BEFORE_FIRST_SESSION, resumesAt);
        log.info("[Course] startCourse : roomCode={} 첫 게임 룰 설명 {}초 후 seq={} 시작",
            room.roomCode(), SESSION_INTERMISSION.toSeconds(), intermission.nextSessionSeq());
        courseEventPublisher.publishIntermission(roomId, intermission);
        taskScheduler.schedule(() -> advance(roomId, BEFORE_FIRST_SESSION), resumesAt);

        // 첫 게임이 실제로 열리는 건 룰 설명이 끝난 뒤지만(GAME_SESSION_STARTED가 그때 나간다),
        // "코스가 시작됐다"는 방장이 시작을 누른 이 순간이다.
        applicationEventPublisher.publishEvent(
            AnalyticsDomainEvent.server(
                AnalyticsEventName.COURSE_STARTED,
                roomId,
                requesterId,
                Instant.now(),
                Map.of(
                    "playerCount", participants.size(),
                    "courseSize", items.size()
                )
            )
        );

        // 응답은 "곧 열릴 게임"이다. 화면 전환은 인터미션 뒤의 game:started가 담당하므로 프론트가
        // 이 값으로 화면을 바꾸진 않지만, 요청이 무엇을 예약했는지는 응답으로도 확인돼야 한다.
        // validatePlayable을 통과했으므로 열 수 있는 칸이 최소 하나는 있다.
        CourseItem first = items.getFirst();
        return new StartedSession(
            requireNonNullElse(intermission.nextGameId(), first.gameId()),
            requireNonNullElse(intermission.nextSessionSeq(), 1),
            requireNonNullElse(intermission.nextRoundCount(), first.roundCount())
        );
    }

    // 게임 하나가 끝났을 때(GameSessionFinishedEvent) 호출된다. 결과를 볼 시간을 준 뒤 다음으로.
    public void scheduleAdvance(UUID roomId, int finishedSeq) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null || room.status() != RoomStatus.PLAYING) {
            return;
        }
        // 지난 세션의 늦은 종료 통보(중복 이벤트, 좀비 타이머)는 무시한다 — 이걸 안 걸면
        // 이미 다음 게임이 진행 중인데 또 넘겨버린다.
        if (finishedSeq != room.currentSessionSeq()) {
            log.info("[Course] scheduleAdvance : roomCode={} 지난 세션(seq={}, 현재={}) 종료 통보 무시",
                room.roomCode(), finishedSeq, room.currentSessionSeq());
            return;
        }

        // 세트가 끝난 사실 자체는 여기서 한 번만 기록한다 — 아래 두 구간은 화면 전환일 뿐이다.
        publishFinishedSessionAnalytics(room, finishedSeq);

        // 두 구간을 순서대로 준다 — 겹치면 룰 설명이 방금 세트의 결과를 덮는다.
        //   1) 세트 중간 결과 (SET_RESULT_DURATION): 방금 끝난 세트의 순위/점수를 읽는 시간.
        //      게임별 종료 이벤트(ninja:game-ended 등)가 이미 나갔으므로 서버가 따로 알릴 것이 없다.
        //   2) 다음 게임 룰 설명 (SESSION_INTERMISSION): course:intermission으로 시작을 알린다.
        Instant rulesAt = Instant.now().plus(SET_RESULT_DURATION);
        log.info("[Course] scheduleAdvance : roomCode={} seq={} 종료 — 세트 결과 {}초 후 룰 설명",
            room.roomCode(), finishedSeq, SET_RESULT_DURATION.toSeconds());
        taskScheduler.schedule(() -> beginRuleIntermission(roomId, finishedSeq), rulesAt);
    }

    // 세트 결과를 보여준 뒤 다음 게임 룰 설명 구간을 연다. 이 시점에 course:intermission이
    // 나가고, 프론트는 그걸 받아 세트 결과 화면을 접고 룰 설명으로 넘어간다(전환 기준이 서버
    // 이벤트라 전원이 같은 순간에 넘어간다).
    void beginRuleIntermission(UUID roomId, int finishedSeq) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null || room.status() != RoomStatus.PLAYING) {
            return;
        }
        // 방장이 세트 결과 화면에서 "다음 세트 시작하기"를 눌러 이미 넘어갔다면 할 일이 없다.
        if (finishedSeq != room.currentSessionSeq()) {
            log.info("[Course] beginRuleIntermission : roomCode={} seq={} 이미 넘어갔다 — 건너뜀",
                room.roomCode(), finishedSeq);
            return;
        }

        Instant resumesAt = Instant.now().plus(SESSION_INTERMISSION);
        // 다음 칸이 곧 다음 게임인 것은 아니라(인원이 안 맞는 칸은 건너뛴다) 서버가 직접 고른다.
        CourseIntermissionPayload payload = buildIntermission(room, finishedSeq, resumesAt);
        if (payload.nextSessionSeq() == null) {
            // 마지막 세트였다 — 설명할 다음 게임이 없으므로 룰 설명 구간을 생략하고 곧바로
            // 종합 결과로 넘어간다. 빈 화면을 8초 더 보여줄 이유가 없다.
            log.info("[Course] beginRuleIntermission : roomCode={} 남은 게임 없음 — 룰 설명 생략",
                room.roomCode());
            advance(roomId, finishedSeq);
            return;
        }

        log.info("[Course] beginRuleIntermission : roomCode={} seq={} 룰 설명 {}초 후 seq={} 시작",
            room.roomCode(), finishedSeq, SESSION_INTERMISSION.toSeconds(),
            payload.nextSessionSeq());
        courseEventPublisher.publishIntermission(roomId, payload);
        taskScheduler.schedule(() -> advance(roomId, finishedSeq), resumesAt);
    }

    // 인터미션 화면의 "바로 시작"(방장 전용). 남은 대기 시간을 건너뛰고 다음 게임을 즉시 연다.
    // 코스 첫 게임 앞 인터미션(finishedSeq=BEFORE_FIRST_SESSION)도 같은 경로로 건너뛴다.
    // 스케줄된 타이머는 그대로 두고 seq compare-and-set으로 경쟁을 정리한다 — 먼저 진행 권한을
    // 얻은 쪽만 세션을 열고, 늦게 깬 타이머는 seq가 이미 바뀌어 있어 조용히 아무것도 하지 않는다.
    public void skipIntermission(UUID roomId, UUID requesterId, int finishedSeq) {
        Room room = requireRoom(roomId);
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (room.status() != RoomStatus.PLAYING) {
            throw new BusinessException(ErrorCode.COURSE_NOT_IN_INTERMISSION);
        }
        // 이미 다음 게임이 열렸거나(타이머가 먼저 돌았다) 엉뚱한 인터미션을 가리키는 요청.
        if (finishedSeq != room.currentSessionSeq()) {
            throw new BusinessException(ErrorCode.COURSE_NOT_IN_INTERMISSION);
        }

        log.info("[Course] skipIntermission : roomCode={} seq={} 방장이 대기 건너뜀",
            room.roomCode(), finishedSeq);
        advance(roomId, finishedSeq);
    }

    // 인터미션 안내용으로 "다음에 열릴 게임"을 미리 계산한다. 실제 진행(advance)과 달리 상태를
    // 바꾸지 않고 건너뛰기 이벤트도 쏘지 않는다 — 순수 조회다.
    private CourseIntermissionPayload buildIntermission(
        Room room,
        int finishedSeq,
        Instant resumesAt
    ) {
        List<CourseItem> items = courseRepository.findAll(room.roomId(), room.roomCode());
        int playerCount = connectedParticipants(room.roomId()).size();
        for (int seq = finishedSeq + 1; seq <= items.size(); seq++) {
            CourseItem item = items.get(seq - 1);
            if (findSkipReason(item, playerCount).isPresent()) {
                continue;
            }
            Game next = gameCatalogService.requireSelectableGame(item.gameId());
            return new CourseIntermissionPayload(
                finishedSeq,
                seq,
                item.gameId(),
                next.getName(),
                // 룰 설명의 원본은 MySQL games.description이다 — 프론트에 문구를 두지 않아
                // 배포 없이 DB만 고쳐도 화면이 바뀐다.
                next.getDescription(),
                item.roundCount(),
                resumesAt,
                true
            );
        }
        // 남은 칸이 없다 — 곧 종합 결과로 넘어간다. 건너뛸 게임이 없으니 스킵도 막는다.
        return new CourseIntermissionPayload(
            finishedSeq, null, null, null, null, null, resumesAt, false
        );
    }

    void advance(UUID roomId, int finishedSeq) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null || room.status() != RoomStatus.PLAYING
            || finishedSeq != room.currentSessionSeq()) {
            return;
        }

        // 진행 권한을 원자적으로 딱 한 번만 가져온다. 인터미션 타이머와 방장의 "바로 시작"이
        // 겹칠 수 있어서, 위의 조회 기반 검사만으로는 둘 다 통과해 같은 게임을 두 번 여는 창이
        // 생긴다. seq를 다음 칸으로 올리는 CAS에 성공한 쪽만 아래를 실행한다.
        if (!roomRepository.tryAdvanceSessionSeq(roomId, finishedSeq, finishedSeq + 1)) {
            log.info("[Course] advance : roomCode={} seq={} 진행 권한 없음 — 이미 넘어갔다",
                room.roomCode(), finishedSeq);
            return;
        }

        List<CourseItem> items = courseRepository.findAll(roomId, room.roomCode());
        List<Participant> participants = connectedParticipants(roomId);

        // 다음 칸부터 훑으며 "지금 인원으로 할 수 있는" 첫 게임을 찾는다. 못 하는 게임은
        // 건너뛰되(코스를 중단하지 않는다) 건너뛴 사실은 알린다.
        for (int seq = finishedSeq + 1; seq <= items.size(); seq++) {
            CourseItem item = items.get(seq - 1);
            Optional<String> skipReason = findSkipReason(item, participants.size());
            if (skipReason.isEmpty()) {
                // 권한은 위에서 이미 잡았으므로 여기선 단순 기록이면 된다(경쟁자가 없다).
                roomRepository.updateCurrentSessionSeq(roomId, seq);
                try {
                    openSession(room, seq, item, participants);
                    return;
                } catch (RuntimeException e) {
                    // 이 메서드는 스케줄러 스레드에서 돈다 — 여기서 예외가 빠져나가면 아무도 잡지
                    // 않고 코스가 PLAYING 상태로 영원히 멈춘다. 그 게임만 건너뛰고 계속 간다.
                    // (게임별 검증이 코스 검증보다 엄격한 경우, 세션을 여는 순간 인원이 또 바뀐 경우 등)
                    log.warn("[Course] advance : roomCode={} seq={} 세션 오픈 실패 — 건너뜀",
                        room.roomCode(), seq, e);
                    skipReason = Optional.of("START_FAILED");
                }
            }

            log.info("[Course] advance : roomCode={} seq={} 건너뜀 — {}",
                room.roomCode(), seq, skipReason.get());
            courseEventPublisher.publishSessionSkipped(
                roomId,
                new CourseSessionSkippedPayload(
                    seq,
                    item.gameId(),
                    gameCatalogService.findName(item.gameId()),
                    skipReason.get()
                )
            );
            applicationEventPublisher.publishEvent(new AnalyticsDomainEvent(
                UUID.randomUUID(),
                AnalyticsEventName.GAME_SESSION_SKIPPED,
                roomId,
                null,
                gameCatalogService.findName(item.gameId()),
                seq,
                null,
                Instant.now(),
                null,
                null,
                null,
                Map.of("reason", skipReason.get())
            ));
        }

        // 첫 게임 앞 인터미션에서 여기까지 왔다면 한 판도 열리지 않았다는 뜻이다(설명을 읽는
        // 8초 사이에 사람이 빠져 전 칸이 인원 미달이 된 경우 등). 0점짜리 종합 결과를 띄우는
        // 대신 대기방으로 되돌려, 인원을 맞춰 다시 시작할 수 있게 한다.
        if (finishedSeq == BEFORE_FIRST_SESSION) {
            log.warn("[Course] advance : roomCode={} 첫 게임을 열지 못했다 — 대기방으로 되돌림",
                room.roomCode());
            roomRepository.updateCurrentSessionSeq(roomId, 1);
            roomRepository.updateStatus(roomId, RoomStatus.WAITING);
            participantRepository.updateAllInLobby(roomId, true);
            courseEventPublisher.publishCourseAborted(roomId);
            return;
        }

        finishCourse(room, items.size());
    }

    private void openSession(
        Room room,
        int seq,
        CourseItem item,
        List<Participant> participants
    ) {
        Game game = gameCatalogService.requireSelectableGame(item.gameId());
        GameSessionStarter starter = startersByGameName.get(game.getName());
        if (starter == null) {
            // 코스 저장 시 막아두므로 정상적으론 도달하지 않는다(게임이 저장 후 비활성화된 경우 등).
            throw new BusinessException(ErrorCode.COURSE_GAME_NOT_SUPPORTED);
        }
        log.info("[Course] openSession : roomCode={} seq={} game={} rounds={} topicId={}",
            room.roomCode(), seq, game.getName(), item.roundCount(), item.topicId());
        // 게임을 시작시키기 전에 세션 키를 먼저 만든다 — 공통 점수 저장이 이 키의 존재로
        // "세션이 열렸는지"를 검증하므로, 게임이 첫 라운드 점수를 저장하는 시점엔 이미 있어야 한다.
        courseRepository.openSession(
            room.roomCode(),
            seq,
            item.gameId(),
            item.roundCount()
        );
        // game:started 브로드캐스트는 각 게임이 세션을 열면서 발행한다 — 프론트의 화면 전환은
        // 그 이벤트 하나로 통일돼 있어서, 코스가 별도 "다음 게임" 이벤트를 만들지 않는다.
        starter.start(
            room.roomId(),
            new GameSessionSpec(item.gameId(), item.roundCount(), item.topicId()),
            participants
        );
        applicationEventPublisher.publishEvent(new AnalyticsDomainEvent(
            UUID.randomUUID(),
            AnalyticsEventName.GAME_SESSION_STARTED,
            room.roomId(),
            null,
            game.getName(),
            seq,
            null,
            Instant.now(),
            null,
            null,
            null,
            Map.of(
                "playerCount", participants.size(),
                "roundCount", item.roundCount()
            )
        ));
    }

    // 코스 종합 결과 화면에서 참가자가 "방으로 돌아가기"를 누르는 지점 — 방장 전용이 아니라
    // 전원이 각자 누른다. 복귀는 개별 행동이므로 한 명이 눌러도 나머지는 결과 화면에 남고,
    // 대기방에서는 아직 안 돌아온 사람의 타일이 "게임 중"으로 자리를 지킨다(방장 자격·입장
    // 순서 모두 유지 — 아무도 방을 떠나지 않기 때문).
    //
    // 방을 WAITING으로 되돌리고 점수를 지우는 일은 가장 먼저 누른 한 명에게만 일어난다(그
    // 뒤엔 이미 WAITING이라 건너뛴다). 코스 항목(room:{code}:course:{idx})은 남긴다 —
    // 같은 구성으로 다시 놀거나 대기방에서 고친다.
    public void returnToLobby(UUID roomId, UUID requesterId) {
        Room room = requireRoom(roomId);
        participantRepository.findById(roomId, requesterId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.ROOM_PARTICIPANT_NOT_FOUND
            ));

        // 코스가 끝난 방(FINISHED)이거나, 먼저 누른 사람이 이미 되돌려 놓은 방(WAITING)에서만
        // 돌아올 수 있다. 진행 중(PLAYING)에는 돌아갈 대기방이 없다.
        boolean reopenRoom = room.status() == RoomStatus.FINISHED;
        if (!reopenRoom && room.status() != RoomStatus.WAITING) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FINISHED);
        }

        if (reopenRoom) {
            log.info("[Course] returnToLobby : roomCode={} 첫 복귀 — 점수 초기화 후 방 재개방",
                room.roomCode());
            // 점수를 먼저 지우고 나서 WAITING으로 되돌린다 — 순서가 반대면 새 코스가 시작될 수
            // 있는 상태에서 이전 점수가 잠깐 남는다.
            gameScoreService.clearCourseResults(roomId);
            // 전원 준비 해제 — 카메라/인식 테스트를 다시 거치게 한다. 각자 복귀하는 시점에
            // 아래에서 자기 준비 상태를 받으므로, 여기서 방장을 특별취급하지 않는다.
            participantRepository.resetAllReady(roomId);
            roomRepository.updateCurrentSessionSeq(roomId, 1);
            roomRepository.updateStatus(roomId, RoomStatus.WAITING);
        }

        // 방장은 준비 토글 대신 "게임 시작" 버튼을 쓴다는 방 생성 시 불변식에 맞춰, 복귀하는
        // 순간 준비 상태로 복원한다. 방장이 먼저 누르든 나중에 누르든 같게 동작한다.
        boolean isHost = room.hostParticipantId().equals(requesterId);
        participantRepository.updateInLobby(roomId, requesterId, true);
        participantRepository.updateReady(roomId, requesterId, isHost);
        log.info("[Course] returnToLobby : roomCode={} participantId={} 대기방 복귀 (방장={})",
            room.roomCode(), requesterId, isHost);

        courseEventPublisher.publishMemberReturned(
            roomId,
            new MemberReturnedPayload(requesterId, isHost, reopenRoom)
        );
    }

    // 건너뛸 이유가 있으면 사람이 읽을 수 있는 문자열로, 없으면 empty.
    private Optional<String> findSkipReason(CourseItem item, int playerCount) {
        Game game = gameCatalogService.requireSelectableGame(item.gameId());
        if (playerCount < game.getMinPlayers()) {
            return Optional.of("NOT_ENOUGH_PLAYERS");
        }
        if (playerCount > game.getMaxPlayers()) {
            return Optional.of("TOO_MANY_PLAYERS");
        }
        if (!startersByGameName.containsKey(game.getName())) {
            return Optional.of("GAME_NOT_SUPPORTED");
        }
        return Optional.empty();
    }

    private void finishCourse(Room room, int totalSessions) {
        log.info("[Course] finishCourse : roomCode={} 코스 {}칸 전부 종료 — 종합 결과 발행",
            room.roomCode(), totalSessions);
        // 방을 FINISHED로 닫는다. 같은 방에서 다시 놀려면 방장이 returnToLobby로 점수를
        // 초기화하고 WAITING으로 되돌린 뒤 코스를 다시 시작한다.
        roomRepository.updateStatus(room.roomId(), RoomStatus.FINISHED);
        List<CourseScoreEntry> ranking = buildRanking(room);
        courseEventPublisher.publishCourseFinished(
            room.roomId(),
            new CourseFinishedPayload(totalSessions, ranking)
        );
        applicationEventPublisher.publishEvent(
            AnalyticsDomainEvent.server(
                AnalyticsEventName.COURSE_FINISHED,
                room.roomId(),
                null,
                Instant.now(),
                Map.of(
                    "playerCount", ranking.size(),
                    "totalSessions",
                    totalSessions
                )
            )
        );
    }

    private void publishFinishedSessionAnalytics(Room room, int finishedSeq) {
        try {
            CourseItem item = courseRepository
                .findAll(room.roomId(), room.roomCode())
                .get(finishedSeq - 1);
            applicationEventPublisher.publishEvent(new AnalyticsDomainEvent(
                UUID.randomUUID(),
                AnalyticsEventName.GAME_SESSION_FINISHED,
                room.roomId(),
                null,
                gameCatalogService.findName(item.gameId()),
                finishedSeq,
                null,
                Instant.now(),
                null,
                null,
                null,
                Map.of()
            ));
        } catch (RuntimeException exception) {
            log.warn(
                "[Analytics] failed to describe finished game session: roomCode={}, seq={}",
                room.roomCode(),
                finishedSeq,
                exception
            );
        }
    }

    // 코스 전체 누적 점수(room:{code}:course:totals) 내림차순. 동점은 같은 순위를 준다.
    private List<CourseScoreEntry> buildRanking(Room room) {
        Map<UUID, Long> totals = gameScoreService.getCourseTotals(room.roomId());
        // 한 판도 점수를 못 낸 참가자도 0점으로 표에 남아야 한다.
        LinkedHashMap<UUID, Long> scores = new LinkedHashMap<>();
        for (Participant participant : participantRepository.findAll(room.roomId())) {
            scores.put(
                participant.participantId(),
                totals.getOrDefault(participant.participantId(), 0L)
            );
        }

        List<Map.Entry<UUID, Long>> sorted = scores.entrySet().stream()
            .sorted(Comparator.comparingLong(
                (Map.Entry<UUID, Long> entry) -> entry.getValue()
            ).reversed())
            .toList();

        List<CourseScoreEntry> ranking = new ArrayList<>(sorted.size());
        long previousScore = Long.MIN_VALUE;
        int previousRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<UUID, Long> entry = sorted.get(index);
            int rank = entry.getValue() == previousScore ? previousRank : index + 1;
            ranking.add(new CourseScoreEntry(entry.getKey(), entry.getValue(), rank));
            previousScore = entry.getValue();
            previousRank = rank;
        }
        return ranking;
    }

    private List<Participant> connectedParticipants(UUID roomId) {
        return participantRepository.findAll(roomId).stream()
            .filter(participant ->
                participant.connectionStatus() == ConnectionStatus.CONNECTED
            )
            .toList();
    }

    private Room requireRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    // 시작 요청에 대한 응답용 — 프론트의 화면 전환은 game:started로 하지만, 요청이 실제로
    // 무엇을 열었는지는 응답으로도 확인할 수 있어야 한다.
    public record StartedSession(Long gameId, int sessionSeq, int totalRounds) {
    }
}
