package com.camon.domain.course.service;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.course.ws.CourseEventPublisher;
import com.camon.domain.course.ws.payload.CourseFinishedPayload;
import com.camon.domain.course.ws.payload.CourseResetPayload;
import com.camon.domain.course.ws.payload.CourseScoreEntry;
import com.camon.domain.course.ws.payload.CourseSessionSkippedPayload;
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
import org.springframework.stereotype.Service;

// 대기방에서 확정된 코스를 실제로 굴리는 오케스트레이터.
//
// 코스 = 게임 여러 개의 줄. 한 게임이 끝나면(GameSessionFinishedEvent) 잠깐 결과를 보여준 뒤
// 다음 칸의 게임 세션을 연다. 마지막 칸까지 끝나면 코스 종합 결과를 발행하고 방을 끝낸다.
//
// 세션 번호(seq)는 코스의 위치(idx)와 1:1이다. 코스가 끝난 방은 FINISHED로 닫히고,
// 방장이 "방으로 돌아가기"(returnToLobby)를 누르면 점수 키(session:{seq}:*, course:totals)를
// 전부 지우고 WAITING으로 되돌린다 — 점수가 HINCRBY로 누적되므로 지우지 않고 seq를
// 재사용하면 이전 코스 점수가 그대로 얹힌다.
@Slf4j
@Service
public class CourseRunner {

    // 한 게임이 끝나고 다음 게임이 열리기까지의 간격. 각 게임의 종료 화면(최종 순위)을 읽을
    // 시간을 준다. 게임 내부 인터미션(닌자 이펙트 5초 + 카운트다운 3초)과는 별개의 구간이다.
    private static final Duration SESSION_INTERMISSION = Duration.ofSeconds(8);

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final CourseRepository courseRepository;
    private final CourseService courseService;
    private final GameCatalogService gameCatalogService;
    private final GameScoreService gameScoreService;
    private final CourseEventPublisher courseEventPublisher;
    private final TaskScheduler taskScheduler;
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
        List<GameSessionStarter> sessionStarters
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.courseRepository = courseRepository;
        this.courseService = courseService;
        this.gameCatalogService = gameCatalogService;
        this.gameScoreService = gameScoreService;
        this.courseEventPublisher = courseEventPublisher;
        this.taskScheduler = taskScheduler;
        this.startersByGameName = sessionStarters.stream().collect(
            Collectors.toUnmodifiableMap(
                GameSessionStarter::gameName,
                Function.identity()
            )
        );
    }

    // 방장이 대기방에서 "게임 시작"을 누르는 지점. 코스의 첫 칸 세션을 연다.
    public StartedSession startCourse(UUID roomId, UUID requesterId) {
        Room room = requireRoom(roomId);
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw new BusinessException(ErrorCode.ROOM_ALREADY_STARTED);
        }

        List<Participant> participants = participantRepository.findAll(roomId);
        // 방장은 방 생성 시 ready=true로 시작하므로(시작 버튼이 곧 준비 의사) 특별취급 없이
        // 전원이 ready인지만 본다.
        if (participants.isEmpty()
            || participants.stream().anyMatch(participant -> !participant.ready())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ALL_READY);
        }

        List<CourseItem> items = courseRepository.findAll(roomId, room.roomCode());
        // 인원 조건은 코스 저장 때가 아니라 여기서 본다 — 저장 시점엔 사람이 계속 드나든다.
        courseService.validatePlayable(items, participants.size());

        int firstSeq = 1;
        // 게임 도메인은 room.currentSessionSeq()로 자기 Redis 키를 조립하므로, 세션을 열기 전에
        // 반드시 seq를 먼저 기록해야 한다. 순서가 뒤바뀌면 엉뚱한 키에 세션이 만들어진다.
        roomRepository.updateCurrentSessionSeq(roomId, firstSeq);
        // status를 먼저 PLAYING으로 올려, game:started가 나갈 시점엔 이미 PLAYING이 되도록 한다
        // (늦게 붙은 클라이언트가 방 status 조회로 게임 화면을 복구할 수 있게).
        roomRepository.updateStatus(roomId, RoomStatus.PLAYING);
        try {
            CourseItem first = items.getFirst();
            openSession(room, firstSeq, first, participants);
            return new StartedSession(first.gameId(), firstSeq, first.roundCount());
        } catch (RuntimeException e) {
            // 세션 오픈 실패 시 대기방으로 되돌려 재시작이 가능하게 한다.
            roomRepository.updateStatus(roomId, RoomStatus.WAITING);
            throw e;
        }
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

        log.info("[Course] scheduleAdvance : roomCode={} seq={} 종료 — {}초 후 다음 진행",
            room.roomCode(), finishedSeq, SESSION_INTERMISSION.toSeconds());
        taskScheduler.schedule(
            () -> advance(roomId, finishedSeq),
            Instant.now().plus(SESSION_INTERMISSION)
        );
    }

    void advance(UUID roomId, int finishedSeq) {
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null || room.status() != RoomStatus.PLAYING
            || finishedSeq != room.currentSessionSeq()) {
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
    }

    // 코스 종합 결과 화면에서 방장이 "방으로 돌아가기"를 누르는 지점. 이전 코스의 점수 기록을
    // 지우고 방을 WAITING으로 되돌려, 같은 방에서 코스를 다시 시작할 수 있게 한다.
    // 코스 항목(room:{code}:course:{idx})은 남긴다 — 같은 구성으로 다시 놀거나 대기방에서 고친다.
    public void returnToLobby(UUID roomId, UUID requesterId) {
        Room room = requireRoom(roomId);
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (room.status() != RoomStatus.FINISHED) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FINISHED);
        }

        log.info("[Course] returnToLobby : roomCode={} 점수 초기화 후 대기방 복귀",
            room.roomCode());
        // 점수를 먼저 지우고 나서 WAITING으로 되돌린다 — 순서가 반대면 새 코스가 시작될 수
        // 있는 상태에서 이전 점수가 잠깐 남는다.
        gameScoreService.clearCourseResults(roomId);
        // 전원 준비 해제(카메라/인식 테스트를 다시 거치게) 후 방장만 준비 상태로 복원한다 —
        // 방장은 준비 토글 대신 "게임 시작" 버튼을 쓴다는 방 생성 시 불변식과 맞춘다.
        participantRepository.resetAllReady(roomId);
        roomRepository.updateCurrentSessionSeq(roomId, 1);
        roomRepository.updateStatus(roomId, RoomStatus.WAITING);
        participantRepository.updateReady(roomId, requesterId, true);

        courseEventPublisher.publishCourseReset(
            roomId,
            new CourseResetPayload(requesterId)
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
        courseEventPublisher.publishCourseFinished(
            room.roomId(),
            new CourseFinishedPayload(totalSessions, buildRanking(room))
        );
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
