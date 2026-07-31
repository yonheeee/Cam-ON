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
import com.camon.domain.course.ws.payload.CourseIntermissionPayload;
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
    void rejectsStartWhenSomeoneIsStillOnTheResultScreen() {
        // 이전 코스 결과를 아직 보고 있는 사람(inLobby=false)이 있으면 시작하지 않는다 —
        // 그대로 열면 결과를 읽는 중에 게임 화면으로 끌려 들어간다. 방장은 복귀 시 ready=true가
        // 되므로 ready 검증만으로는 이 경우가 안 걸린다.
        givenRoom(RoomStatus.WAITING, 1);
        List<Participant> participants = new ArrayList<>(readyParticipants(2));
        participants.add(stillOnResultScreen());
        when(participantRepository.findAll(roomId)).thenReturn(participants);

        assertBusinessError(
            () -> runner.startCourse(roomId, hostId),
            ErrorCode.ROOM_NOT_ALL_RETURNED
        );
        verify(roomRepository, never()).updateStatus(any(), eq(RoomStatus.PLAYING));
    }

    @Test
    void clearsInLobbyForEveryoneWhenCourseStarts() {
        givenRoom(RoomStatus.WAITING, 1);
        when(participantRepository.findAll(roomId)).thenReturn(readyParticipants(4));
        givenCourse(new CourseItem(1, NINJA_ID, 3, null));
        givenGame(NINJA_ID, "NINJA", 2, 4);

        runner.startCourse(roomId, hostId);

        verify(participantRepository).updateAllInLobby(roomId, false);
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
    void announcesIntermissionWithNextGameRulesFromDatabase() {
        // 인터미션 화면이 띄울 다음 게임 이름·룰 설명은 서버가 games 테이블에서 읽어 내려준다.
        // (프론트에 설명 문구를 두지 않기 위한 것 — DB만 고쳐도 화면이 바뀌어야 한다)
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(4);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4, "표현자가 말없이 몸으로 설명합니다.");

        runner.scheduleAdvance(roomId, 1);

        ArgumentCaptor<CourseIntermissionPayload> captor = ArgumentCaptor.captor();
        verify(courseEventPublisher).publishIntermission(eq(roomId), captor.capture());
        CourseIntermissionPayload payload = captor.getValue();
        assertThat(payload.finishedSessionSeq()).isEqualTo(1);
        assertThat(payload.nextSessionSeq()).isEqualTo(2);
        assertThat(payload.nextGameName()).isEqualTo("CHARADES");
        assertThat(payload.nextGameDescription())
            .isEqualTo("표현자가 말없이 몸으로 설명합니다.");
        assertThat(payload.nextRoundCount()).isEqualTo(2);
        assertThat(payload.skippable()).isTrue();
    }

    @Test
    void announcesIntermissionWithoutNextGameWhenCourseIsAboutToEnd() {
        // 마지막 게임 뒤의 인터미션 — 건너뛸 다음 게임이 없으므로 스킵도 막아야 한다.
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(2);
        givenCourse(new CourseItem(1, NINJA_ID, 3, null));

        runner.scheduleAdvance(roomId, 1);

        ArgumentCaptor<CourseIntermissionPayload> captor = ArgumentCaptor.captor();
        verify(courseEventPublisher).publishIntermission(eq(roomId), captor.capture());
        assertThat(captor.getValue().nextSessionSeq()).isNull();
        assertThat(captor.getValue().nextGameDescription()).isNull();
        assertThat(captor.getValue().skippable()).isFalse();
    }

    @Test
    void intermissionAnnouncementSkipsGameThatPlayerCountCannotRun() {
        // 다음 칸이 곧 다음 게임인 게 아니다 — 인원이 안 맞는 칸은 건너뛴 결과를 알려야
        // 프론트가 코스를 보고 추측하지 않는다.
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(2);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID),
            new CourseItem(3, NINJA_ID, 2, null)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4);
        givenGame(NINJA_ID, "NINJA", 2, 4, "손동작 콤보를 가장 빨리 완성하세요.");

        runner.scheduleAdvance(roomId, 1);

        ArgumentCaptor<CourseIntermissionPayload> captor = ArgumentCaptor.captor();
        verify(courseEventPublisher).publishIntermission(eq(roomId), captor.capture());
        assertThat(captor.getValue().nextSessionSeq()).isEqualTo(3);
        assertThat(captor.getValue().nextGameName()).isEqualTo("NINJA");
        assertThat(captor.getValue().nextGameDescription())
            .isEqualTo("손동작 콤보를 가장 빨리 완성하세요.");
        // 안내는 순수 조회다 — 건너뛰기 이벤트를 쏘거나 seq를 건드리면 안 된다.
        verify(courseEventPublisher, never()).publishSessionSkipped(any(), any());
        verify(roomRepository, never()).updateCurrentSessionSeq(any(), anyInt());
    }

    @Test
    void hostCanSkipIntermissionToOpenNextGameImmediately() {
        givenRoom(RoomStatus.PLAYING, 1);
        givenConnected(4);
        givenCourse(
            new CourseItem(1, NINJA_ID, 3, null),
            new CourseItem(2, CHARADES_ID, 2, TOPIC_ID)
        );
        givenGame(CHARADES_ID, "CHARADES", 3, 4);

        runner.skipIntermission(roomId, hostId, 1);

        assertThat(charadesStarter.specs).containsExactly(
            new GameSessionSpec(CHARADES_ID, 2, TOPIC_ID)
        );
        verify(roomRepository).updateCurrentSessionSeq(roomId, 2);
    }

    @Test
    void rejectsIntermissionSkipByNonHost() {
        givenRoom(RoomStatus.PLAYING, 1);

        assertBusinessError(
            () -> runner.skipIntermission(roomId, UUID.randomUUID(), 1),
            ErrorCode.ROOM_NOT_HOST
        );
        assertThat(charadesStarter.specs).isEmpty();
        assertThat(ninjaStarter.specs).isEmpty();
    }

    @Test
    void rejectsIntermissionSkipForAlreadyPassedIntermission() {
        // 타이머가 먼저 돌아 2번 게임이 이미 열린 뒤의 늦은 클릭. 그냥 "지금 넘겨"로 처리하면
        // 방금 시작한 게임을 날려버린다.
        givenRoom(RoomStatus.PLAYING, 2);

        assertBusinessError(
            () -> runner.skipIntermission(roomId, hostId, 1),
            ErrorCode.COURSE_NOT_IN_INTERMISSION
        );
        assertThat(charadesStarter.specs).isEmpty();
        assertThat(ninjaStarter.specs).isEmpty();
    }

    @Test
    void opensNothingWhenAdvancePermitIsLostToTheOtherTrigger() {
        // 타이머와 방장 클릭이 겹쳐 CAS에서 밀린 쪽. 조회 기반 검사는 통과했더라도 여기서
        // 멈춰야 같은 게임이 두 번 열리지 않는다.
        givenRoom(RoomStatus.PLAYING, 1);
        givenAdvanceLost();

        runner.advance(roomId, 1);

        assertThat(charadesStarter.specs).isEmpty();
        assertThat(ninjaStarter.specs).isEmpty();
        verify(roomRepository, never()).updateCurrentSessionSeq(any(), anyInt());
        verify(courseEventPublisher, never()).publishCourseFinished(any(), any());
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

    @Test
    void returnToLobby_firstReturnerClearsScoresAndReopensRoom() {
        givenRoom(RoomStatus.FINISHED, 3);
        givenParticipant(hostId);

        runner.returnToLobby(roomId, hostId);

        // 점수 초기화가 WAITING 복귀보다 먼저여야 한다 — 반대면 새 코스가 시작될 수 있는
        // 상태에서 이전 점수가 잠깐 남는다. 방장 ready 복원은 WAITING 이후여야 성립한다.
        InOrder order = Mockito.inOrder(
            gameScoreService, participantRepository, roomRepository
        );
        order.verify(gameScoreService).clearCourseResults(roomId);
        order.verify(participantRepository).resetAllReady(roomId);
        order.verify(roomRepository).updateCurrentSessionSeq(roomId, 1);
        order.verify(roomRepository).updateStatus(roomId, RoomStatus.WAITING);
        order.verify(participantRepository).updateInLobby(roomId, hostId, true);
        order.verify(participantRepository).updateReady(roomId, hostId, true);

        MemberReturnedPayload payload = capturedReturnPayload();
        assertThat(payload.participantId()).isEqualTo(hostId);
        assertThat(payload.roomReopened()).isTrue();
        assertThat(payload.ready()).isTrue();
    }

    @Test
    void returnToLobby_allowsNonHostAndLeavesThemUnready() {
        // 방장 전용이 아니다 — 팀원도 각자 돌아온다. 다만 준비는 다시 해야 하므로 ready=false.
        UUID memberId = UUID.randomUUID();
        givenRoom(RoomStatus.FINISHED, 3);
        givenParticipant(memberId);

        runner.returnToLobby(roomId, memberId);

        verify(participantRepository).updateInLobby(roomId, memberId, true);
        verify(participantRepository).updateReady(roomId, memberId, false);
        assertThat(capturedReturnPayload().ready()).isFalse();
    }

    @Test
    void returnToLobby_laterReturnerDoesNotClearScoresAgain() {
        // 먼저 누른 사람이 이미 WAITING으로 되돌려 놓은 방. 두 번째 사람은 자기 자리만 바꾼다 —
        // 여기서 또 초기화하면 먼저 돌아와 준비까지 마친 사람들의 준비가 풀린다.
        UUID memberId = UUID.randomUUID();
        givenRoom(RoomStatus.WAITING, 1);
        givenParticipant(memberId);

        runner.returnToLobby(roomId, memberId);

        verify(gameScoreService, never()).clearCourseResults(any());
        verify(participantRepository, never()).resetAllReady(any());
        verify(roomRepository, never()).updateStatus(any(), any());
        verify(participantRepository).updateInLobby(roomId, memberId, true);
        assertThat(capturedReturnPayload().roomReopened()).isFalse();
    }

    @Test
    void returnToLobby_rejectsWhileCourseIsPlaying() {
        givenRoom(RoomStatus.PLAYING, 2);
        givenParticipant(hostId);

        assertBusinessError(
            () -> runner.returnToLobby(roomId, hostId),
            ErrorCode.ROOM_NOT_FINISHED
        );
        verify(roomRepository, never()).updateStatus(any(), any());
        verify(gameScoreService, never()).clearCourseResults(any());
    }

    @Test
    void returnToLobby_rejectsSomeoneWhoIsNotInTheRoom() {
        givenRoom(RoomStatus.FINISHED, 3);

        assertBusinessError(
            () -> runner.returnToLobby(roomId, UUID.randomUUID()),
            ErrorCode.ROOM_PARTICIPANT_NOT_FOUND
        );
        verify(gameScoreService, never()).clearCourseResults(any());
    }

    private MemberReturnedPayload capturedReturnPayload() {
        ArgumentCaptor<MemberReturnedPayload> payload =
            ArgumentCaptor.forClass(MemberReturnedPayload.class);
        verify(courseEventPublisher).publishMemberReturned(eq(roomId), payload.capture());
        return payload.getValue();
    }

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
        // 기본은 "진행 권한을 얻었다" — 경쟁에서 밀리는 경우는 givenAdvanceLost()로 따로 만든다.
        lenient().when(roomRepository.tryAdvanceSessionSeq(eq(roomId), anyInt(), anyInt()))
            .thenReturn(true);
    }

    /** 인터미션 타이머와 방장의 "바로 시작"이 겹쳐 이 호출자가 진행 권한을 못 얻은 상황. */
    private void givenAdvanceLost() {
        when(roomRepository.tryAdvanceSessionSeq(eq(roomId), anyInt(), anyInt()))
            .thenReturn(false);
    }

    private void givenParticipant(UUID participantId) {
        when(participantRepository.findById(roomId, participantId))
            .thenReturn(Optional.of(participantWithId(participantId)));
    }

    private void givenCourse(CourseItem... items) {
        when(courseRepository.findAll(roomId, ROOM_CODE)).thenReturn(List.of(items));
    }

    private void givenConnected(int count) {
        lenient().when(participantRepository.findAll(roomId)).thenReturn(readyParticipants(count));
    }

    private void givenGame(Long gameId, String name, int minPlayers, int maxPlayers) {
        givenGame(gameId, name, minPlayers, maxPlayers, null);
    }

    // description은 인터미션 안내에 실려 나가는 룰 설명(games.description)이다.
    private void givenGame(
        Long gameId,
        String name,
        int minPlayers,
        int maxPlayers,
        String description
    ) {
        lenient().when(gameCatalogService.requireSelectableGame(gameId)).thenReturn(
            Game.builder()
                .gameId(gameId)
                .name(name)
                .description(description)
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

    /** 준비는 됐지만 아직 코스 결과 화면에 남아 있는 참가자. */
    private Participant stillOnResultScreen() {
        return new Participant(
            UUID.randomUUID(),
            "watching",
            true,
            ConnectionStatus.CONNECTED,
            Instant.parse("2026-07-28T00:00:00Z"),
            false
        );
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
