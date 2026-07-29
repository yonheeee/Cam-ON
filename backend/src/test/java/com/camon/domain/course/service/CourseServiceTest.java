package com.camon.domain.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.dto.CourseItemRequest;
import com.camon.domain.course.dto.CourseResponse;
import com.camon.domain.course.dto.UpdateCourseRequest;
import com.camon.domain.course.repository.CourseReplaceResult;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.course.ws.CourseEventPublisher;
import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.common.service.GameCatalogService;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

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
    private GameCatalogService gameCatalogService;
    @Mock
    private MissionTopicRepository missionTopicRepository;
    @Mock
    private CourseEventPublisher courseEventPublisher;

    private CourseService service;
    private UUID roomId;
    private UUID hostId;

    @BeforeEach
    void setUp() {
        service = new CourseService(
            roomRepository,
            participantRepository,
            courseRepository,
            gameCatalogService,
            missionTopicRepository,
            courseEventPublisher
        );
        roomId = UUID.randomUUID();
        hostId = UUID.randomUUID();
    }

    @Test
    void savesCourseAndBroadcastsToEveryone() {
        givenRoom(RoomStatus.WAITING);
        givenGame(NINJA_ID, "NINJA", 2, 4, 1, 1, false);
        when(courseRepository.replace(eq(roomId), eq(ROOM_CODE), any()))
            .thenReturn(CourseReplaceResult.SUCCESS);
        when(gameCatalogService.findName(NINJA_ID)).thenReturn("NINJA");

        CourseResponse response = service.updateCourse(
            roomId,
            hostId,
            new UpdateCourseRequest(List.of(new CourseItemRequest(NINJA_ID, null)))
        );

        // 리스트 순서가 곧 진행 순서라 idx가 1부터 채워져야 한다.
        ArgumentCaptor<List<CourseItem>> captor = ArgumentCaptor.captor();
        verify(courseRepository).replace(eq(roomId), eq(ROOM_CODE), captor.capture());
        assertThat(captor.getValue()).containsExactly(new CourseItem(1, NINJA_ID, 1, null));
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().gameName()).isEqualTo("NINJA");
        // 상태 변경 직후 같은 흐름에서 전파까지 끝나야 한다.
        verify(courseEventPublisher).publishCourseUpdated(eq(roomId), any(CourseResponse.class));
    }

    @Test
    void rejectsEditByNonHost() {
        givenRoom(RoomStatus.WAITING);

        assertBusinessError(
            () -> service.updateCourse(
                roomId,
                UUID.randomUUID(),
                new UpdateCourseRequest(List.of(new CourseItemRequest(NINJA_ID, null)))
            ),
            ErrorCode.ROOM_NOT_HOST
        );
        verify(courseRepository, never()).replace(any(), any(), any());
    }

    @Test
    void rejectsGameThatServerCannotStart() {
        givenRoom(RoomStatus.WAITING);
        // games에 row는 있지만 세션을 여는 구현체가 없는 게임(물건 가져오기)
        givenGame(3L, "FETCH_OBJECT", 2, 4, 5, 5, false);
        when(gameCatalogService.isSupported("FETCH_OBJECT")).thenReturn(false);

        assertBusinessError(
            () -> service.updateCourse(
                roomId,
                hostId,
                new UpdateCourseRequest(List.of(new CourseItemRequest(3L, null)))
            ),
            ErrorCode.COURSE_GAME_NOT_SUPPORTED
        );
    }

    @Test
    void fillsRoundsPerSetFromCatalogNotFromClient() {
        // 코스 항목 = 1세트. 라운드 수는 클라이언트가 못 정하고 카탈로그의 고정값(물건 5)이 들어간다.
        givenRoom(RoomStatus.WAITING);
        givenGame(3L, "FETCH_OBJECT", 2, 4, 5, 5, false);
        when(courseRepository.replace(eq(roomId), eq(ROOM_CODE), any()))
            .thenReturn(CourseReplaceResult.SUCCESS);
        when(gameCatalogService.findName(3L)).thenReturn("FETCH_OBJECT");

        service.updateCourse(
            roomId,
            hostId,
            new UpdateCourseRequest(List.of(new CourseItemRequest(3L, null)))
        );

        ArgumentCaptor<List<CourseItem>> captor = ArgumentCaptor.captor();
        verify(courseRepository).replace(eq(roomId), eq(ROOM_CODE), captor.capture());
        assertThat(captor.getValue()).containsExactly(new CourseItem(1, 3L, 5, null));
    }

    @Test
    void requiresTopicForTopicBasedGame() {
        givenRoom(RoomStatus.WAITING);
        givenGame(CHARADES_ID, "CHARADES", 3, 4, 1, 1, true);

        assertBusinessError(
            () -> service.updateCourse(
                roomId,
                hostId,
                new UpdateCourseRequest(List.of(new CourseItemRequest(CHARADES_ID, null)))
            ),
            ErrorCode.COURSE_TOPIC_REQUIRED
        );
    }

    @Test
    void rejectsTopicOnGameThatDoesNotUseTopics() {
        givenRoom(RoomStatus.WAITING);
        givenGame(NINJA_ID, "NINJA", 2, 4, 1, 1, false);

        assertBusinessError(
            () -> service.updateCourse(
                roomId,
                hostId,
                new UpdateCourseRequest(List.of(new CourseItemRequest(NINJA_ID, TOPIC_ID)))
            ),
            ErrorCode.COURSE_TOPIC_NOT_ALLOWED
        );
    }

    @Test
    void rejectsCourseLongerThanLimit() {
        givenRoom(RoomStatus.WAITING);
        List<CourseItemRequest> tooMany = java.util.Collections.nCopies(
            8,
            new CourseItemRequest(NINJA_ID, null)
        );

        assertBusinessError(
            () -> service.updateCourse(roomId, hostId, new UpdateCourseRequest(tooMany)),
            ErrorCode.COURSE_TOO_LONG
        );
    }

    @Test
    void rejectsEditAfterGameStarted() {
        givenRoom(RoomStatus.WAITING);
        givenGame(NINJA_ID, "NINJA", 2, 4, 1, 1, false);
        // 서비스가 검사한 뒤 저장 사이에 게임이 시작된 경합 — Lua 가드가 잡아낸다.
        when(courseRepository.replace(eq(roomId), eq(ROOM_CODE), any()))
            .thenReturn(CourseReplaceResult.ROOM_NOT_WAITING);

        assertBusinessError(
            () -> service.updateCourse(
                roomId,
                hostId,
                new UpdateCourseRequest(List.of(new CourseItemRequest(NINJA_ID, null)))
            ),
            ErrorCode.ROOM_ALREADY_STARTED
        );
        verify(courseEventPublisher, never())
            .publishCourseUpdated(any(), any());
    }

    @Test
    void rejectsStartWhenCourseIsEmpty() {
        assertBusinessError(
            () -> service.validatePlayable(List.of(), 4),
            ErrorCode.COURSE_EMPTY
        );
    }

    @Test
    void rejectsStartWhenPlayerCountIsOutsideGameRange() {
        // 몸으로 말해요는 3~4명. 2명으로는 시작할 수 없다.
        givenGame(CHARADES_ID, "CHARADES", 3, 4, 1, 1, true);

        assertBusinessError(
            () -> service.validatePlayable(
                List.of(new CourseItem(1, CHARADES_ID, 2, TOPIC_ID)),
                2
            ),
            ErrorCode.COURSE_PLAYERS_NOT_ELIGIBLE
        );
    }

    private void givenRoom(RoomStatus status) {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(new Room(
            roomId,
            ROOM_CODE,
            hostId,
            4,
            status,
            1,
            Instant.parse("2026-07-28T00:00:00Z")
        )));
    }

    private void givenGame(
        Long gameId,
        String name,
        int minPlayers,
        int maxPlayers,
        Integer minRounds,
        Integer maxRounds,
        boolean requiresTopic
    ) {
        Game game = Game.builder()
            .gameId(gameId)
            .name(name)
            .minPlayers(minPlayers)
            .maxPlayers(maxPlayers)
            .minRounds(minRounds)
            .maxRounds(maxRounds)
            .isActive(true)
            .build();
        when(gameCatalogService.requireSelectableGame(gameId)).thenReturn(game);
        org.mockito.Mockito.lenient()
            .when(gameCatalogService.isSupported(name)).thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(gameCatalogService.hasTopics(gameId)).thenReturn(requiresTopic);
        if (requiresTopic) {
            org.mockito.Mockito.lenient().when(
                missionTopicRepository.existsByTopicIdAndGameGameIdAndIsActiveTrue(
                    anyLong(),
                    eq(gameId)
                )
            ).thenReturn(true);
        }
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
}
