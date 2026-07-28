package com.camon.domain.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.course.ws.CourseEventPublisher;
import com.camon.domain.course.ws.payload.CourseFinishedPayload;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class CourseRunnerTest {

    private static final String ROOM_CODE = "C0URS3";
    private static final Long NINJA_ID = 1L;
    private static final Long CHARADES_ID = 2L;
    private static final Long TOPIC_ID = 7L;

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private CourseService courseService;
    @Mock
    private GameCatalogService gameCatalogService;
    @Mock
    private GameScoreService gameScoreService;
    @Mock
    private CourseEventPublisher courseEventPublisher;
    @Mock
    private TaskScheduler taskScheduler;

    private RecordingStarter ninjaStarter;
    private RecordingStarter charadesStarter;
    private CourseRunner runner;
    private UUID roomId;
    private UUID hostId;

    @BeforeEach
    void setUp() {
        ninjaStarter = new RecordingStarter("NINJA");
        charadesStarter = new RecordingStarter("CHARADES");
        runner = new CourseRunner(
            roomRepository,
            participantRepository,
            courseRepository,
            courseService,
            gameCatalogService,
            gameScoreService,
            courseEventPublisher,
            taskScheduler,
            List.of(ninjaStarter, charadesStarter)
        );
        roomId = UUID.randomUUID();
        hostId = UUID.randomUUID();
    }

    @Test
    void startsFirstGameOfCourse() {
        List<Participant> participants = readyParticipants(4);
        givenRoom(RoomStatus.WAITING, 1);
        when(participantRepository.findAll(roomId)).thenReturn(participants);
        givenCourse(new CourseItem(1, CHARADES_ID, 2, TOPIC_ID));
        givenGame(CHARADES_ID, "CHARADES", 3, 4);

        CourseRunner.StartedSession started = runner.startCourse(roomId, hostId);

        assertThat(started.gameId()).isEqualTo(CHARADES_ID);
        assertThat(started.sessionSeq()).isEqualTo(1);
        assertThat(started.totalRounds()).isEqualTo(2);
        // 코스가 정한 라운드 수·주제가 그대로 게임에 전달돼야 한다.
        assertThat(charadesStarter.specs).containsExactly(
            new GameSessionSpec(CHARADES_ID, 2, TOPIC_ID)
        );
        assertThat(ninjaStarter.specs).isEmpty();
        // 게임 도메인이 room.currentSessionSeq()로 자기 키를 조립하므로 seq가 먼저 기록돼야 한다.
        InOrder order = Mockito.inOrder(roomRepository);
        order.verify(roomRepository).updateCurrentSessionSeq(roomId, 1);
        order.verify(roomRepository).updateStatus(roomId, RoomStatus.PLAYING);
    }

    @Test
    void rejectsStartByNonHost() {
        givenRoom(RoomStatus.WAITING, 1);

        assertBusinessError(
            () -> runner.startCourse(roomId, UUID.randomUUID()),
            ErrorCode.ROOM_NOT_HOST
        );
        assertThat(ninjaStarter.specs).isEmpty();
    }

    @Test
    void rejectsStartWhenSomeoneIsNotReady() {
        givenRoom(RoomStatus.WAITING, 1);
        List<Participant> participants = new ArrayList<>(readyParticipants(2));
        participants.add(participant(false));
        when(participantRepository.findAll(roomId)).thenReturn(participants);

        assertBusinessError(
            () -> runner.startCourse(roomId, hostId),
            ErrorCode.ROOM_NOT_ALL_READY
        );
        verify(roomRepository, never()).updateStatus(any(), eq(RoomStatus.PLAYING));
    }

    @Test
    void returnsRoomToWaitingWhenOpeningFirstSessionFails() {
        givenRoom(RoomStatus.WAITING, 1);
        when(participantRepository.findAll(roomId)).thenReturn(readyParticipants(4));
        givenCourse(new CourseItem(1, NINJA_ID, 3, null));
        givenGame(NINJA_ID, "NINJA", 2, 4);
        ninjaStarter.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> runner.startCourse(roomId, hostId))
            .isInstanceOf(IllegalStateException.class);

        // 실패한 방이 PLAYING에 갇히면 재시작이 불가능하다.
        verify(roomRepository).updateStatus(roomId, RoomStatus.WAITING);
    }

    @Test
    void opensNextGameWhenOneFinishes() {
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(4);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4);

        runner.advance(roomId, 1);

        verify(roomRepository).updateCurrentSessionSeq(roomId, 2);
        assertThat(charadesStarter.specs).containsExactly(
            new GameSessionSpec(CHARADES_ID, 2, TOPIC_ID)
        );
        verify(courseEventPublisher, never()).publishCourseFinished(any(), any());
    }

    @Test
    void finishesCourseAfterLastGame() {
        givenRoom(RoomStatus.PLAYING, 2);
        givenConnected(2);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, NINJA_ID, 2, null)
        );
        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            participantWithId(winner),
            participantWithId(loser)
        ));
        when(gameScoreService.getCourseTotals(roomId))
            .thenReturn(Map.of(winner, 12L, loser, 5L));

        runner.advance(roomId, 2);

        verify(roomRepository).updateStatus(roomId, RoomStatus.FINISHED);
        ArgumentCaptor<CourseFinishedPayload> captor = ArgumentCaptor.captor();
        verify(courseEventPublisher).publishCourseFinished(eq(roomId), captor.capture());
        assertThat(captor.getValue().totalSessions()).isEqualTo(2);
        // 코스 누적 점수 내림차순이 최종 순위다.
        assertThat(captor.getValue().ranking()).extracting("participantId")
            .containsExactly(winner, loser);
        assertThat(captor.getValue().ranking().getFirst().rank()).isEqualTo(1);
    }

    @Test
    void skipsGameThatCurrentPlayerCountCannotRun() {
        givenRoom(RoomStatus.PLAYING, 1);
        // 4명으로 시작했지만 2명만 남았다 → 몸으로 말해요(3~4명)는 건너뛰고 닌자로 넘어간다.
        givenConnected(2);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID),
            new CourseItem(3, NINJA_ID, 2, null)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4);
        givenGame(NINJA_ID, "NINJA", 2, 4);
        when(gameCatalogService.findName(CHARADES_ID)).thenReturn("CHARADES");

        runner.advance(roomId, 1);

        ArgumentCaptor<CourseSessionSkippedPayload> captor = ArgumentCaptor.captor();
        verify(courseEventPublisher).publishSessionSkipped(eq(roomId), captor.capture());
        assertThat(captor.getValue().sessionSeq()).isEqualTo(2);
        assertThat(captor.getValue().reason()).isEqualTo("NOT_ENOUGH_PLAYERS");
        // 코스를 중단하지 않고 그 다음 게임을 연다.
        verify(roomRepository).updateCurrentSessionSeq(roomId, 3);
        assertThat(ninjaStarter.specs).containsExactly(new GameSessionSpec(NINJA_ID, 2, null));
        assertThat(charadesStarter.specs).isEmpty();
    }

    @Test
    void skipsGameWhoseSessionFailsToOpenInsteadOfStalling() {
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(3);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID),
            new CourseItem(3, NINJA_ID, 2, null)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4);
        givenGame(NINJA_ID, "NINJA", 2, 4);
        lenient().when(gameCatalogService.findName(CHARADES_ID)).thenReturn("CHARADES");
        // 게임별 검증이 코스 검증보다 엄격해 세션 오픈이 터지는 경우(제시어 부족 등).
        charadesStarter.failure = new BusinessException(
            ErrorCode.CHARADES_NOT_ENOUGH_MISSIONS
        );

        runner.advance(roomId, 1);

        // 스케줄러 스레드에서 예외가 빠져나가면 코스가 PLAYING으로 멈춘다 — 건너뛰고 계속 가야 한다.
        verify(courseEventPublisher).publishSessionSkipped(eq(roomId), any());
        assertThat(ninjaStarter.specs).containsExactly(new GameSessionSpec(NINJA_ID, 2, null));
        verify(roomRepository).updateCurrentSessionSeq(roomId, 3);
    }

    @Test
    void finishesCourseWhenEveryRemainingGameIsUnplayable() {
        givenRoom(RoomStatus.PLAYING, 1);
        // 1명만 남으면 어떤 게임도 최소 인원을 못 채운다 → 코스를 끝내고 종합 결과로 간다.
        givenConnected(1);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, NINJA_ID, 2, null)
        );
        givenGame(NINJA_ID, "NINJA", 2, 4);
        lenient().when(gameCatalogService.findName(NINJA_ID)).thenReturn("NINJA");
        when(gameScoreService.getCourseTotals(roomId)).thenReturn(Map.of());

        runner.advance(roomId, 1);

        verify(courseEventPublisher).publishSessionSkipped(eq(roomId), any());
        verify(roomRepository).updateStatus(roomId, RoomStatus.FINISHED);
        verify(courseEventPublisher).publishCourseFinished(eq(roomId), any());
    }

    @Test
    void ignoresFinishNoticeFromAlreadyPassedSession() {
        // 이미 2번째 게임이 진행 중인데 1번째의 늦은 종료 통보가 도착한 경우(중복 이벤트/좀비 타이머).
        givenRoom(RoomStatus.PLAYING, 2);

        runner.advance(roomId, 1);

        verify(roomRepository, never()).updateCurrentSessionSeq(any(), anyInt());
        assertThat(ninjaStarter.specs).isEmpty();
        assertThat(charadesStarter.specs).isEmpty();
    }

    @Test
    void ignoresFinishNoticeWhenRoomIsNoLongerPlaying() {
        givenRoom(RoomStatus.FINISHED, 2);

        runner.advance(roomId, 2);

        verify(roomRepository, never()).updateCurrentSessionSeq(any(), anyInt());
        verify(courseEventPublisher, never()).publishCourseFinished(any(), any());
    }

    // --- fixtures ---

    private void givenRoom(RoomStatus status, int currentSessionSeq) {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(new Room(
            roomId,
            ROOM_CODE,
            hostId,
            4,
            status,
            currentSessionSeq,
            Instant.parse("2026-07-28T00:00:00Z")
        )));
    }

    private void givenCourse(CourseItem... items) {
        when(courseRepository.findAll(roomId, ROOM_CODE)).thenReturn(List.of(items));
    }

    private void givenConnected(int count) {
        lenient().when(participantRepository.findAll(roomId)).thenReturn(readyParticipants(count));
    }

    private void givenGame(Long gameId, String name, int minPlayers, int maxPlayers) {
        lenient().when(gameCatalogService.requireSelectableGame(gameId)).thenReturn(
            Game.builder()
                .gameId(gameId)
                .name(name)
                .minPlayers(minPlayers)
                .maxPlayers(maxPlayers)
                .minRounds(1)
                .maxRounds(10)
                .isActive(true)
                .build()
        );
    }

    private List<Participant> readyParticipants(int count) {
        List<Participant> participants = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            participants.add(participant(true));
        }
        return participants;
    }

    private Participant participant(boolean ready) {
        return participantWithId(UUID.randomUUID(), ready);
    }

    private Participant participantWithId(UUID participantId) {
        return participantWithId(participantId, true);
    }

    private Participant participantWithId(UUID participantId, boolean ready) {
        return new Participant(
            participantId,
            "nick-" + participantId.toString().substring(0, 4),
            ready,
            ConnectionStatus.CONNECTED,
            Instant.parse("2026-07-28T00:00:00Z")
        );
    }

    private static void assertBusinessError(
        org.junit.jupiter.api.function.Executable executable,
        ErrorCode expected
    ) {
        assertThatThrownBy(executable::execute)
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).errorCode())
            .isEqualTo(expected);
    }

    // 어떤 spec으로 세션이 열렸는지 기록하는 테스트용 스타터. Mockito 목보다 이게 읽기 쉽다
    // (게임별로 무엇이 몇 번 호출됐는지가 이 클래스 테스트의 핵심 관심사라서).
    private static final class RecordingStarter implements GameSessionStarter {
        private final String gameName;
        private final List<GameSessionSpec> specs = new ArrayList<>();
        private RuntimeException failure;

        private RecordingStarter(String gameName) {
            this.gameName = gameName;
        }

        @Override
        public String gameName() {
            return gameName;
        }

        @Override
        public void start(
            UUID roomId,
            GameSessionSpec spec,
            List<Participant> participants
        ) {
            if (failure != null) {
                throw failure;
            }
            specs.add(spec);
        }
    }
}
