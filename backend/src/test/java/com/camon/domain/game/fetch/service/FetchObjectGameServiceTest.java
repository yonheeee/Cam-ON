package com.camon.domain.game.fetch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.dto.FetchObjectStateResponse;
import com.camon.domain.game.fetch.repository.FetchObjectRoundState;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.game.fetch.repository.FetchObjectSubmissionRecord;
import com.camon.domain.game.fetch.repository.FetchSubmissionClaimResult;
import com.camon.domain.game.fetch.repository.FetchSubmissionStatus;
import com.camon.domain.game.fetch.ws.FetchObjectEventPublisher;
import com.camon.domain.game.fetch.ws.payload.FetchGameEndedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundStartedPayload;
import com.camon.domain.game.fetch.ws.payload.FetchRoundSuccessPayload;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class FetchObjectGameServiceTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T03:00:00Z");
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final String ROOM_CODE = "AB12CD";
    private static final int SESSION_SEQ = 2;
    private static final Long GAME_ID = 2L;

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private MissionRepository missionRepository;
    @Mock
    private FetchObjectRedisRepository fetchRedis;
    @Mock
    private GameEventPublisher gameEventPublisher;
    @Mock
    private FetchObjectEventPublisher fetchEventPublisher;
    @Mock
    private GameScoreService gameScoreService;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private final List<Runnable> scheduledTasks = new ArrayList<>();
    private final AtomicReference<List<Long>> initializedMissionOrder =
        new AtomicReference<>();
    private final Map<Long, Mission> missionById = new HashMap<>();
    private ScheduledFuture<?> scheduledFuture;
    private FetchObjectGameService service;
    private Room room;
    private List<Participant> participants;

    @BeforeEach
    void setUp() {
        room = new Room(
            ROOM_ID,
            ROOM_CODE,
            UUID.randomUUID(),
            4,
            RoomStatus.PLAYING,
            SESSION_SEQ,
            NOW
        );
        participants = List.of(
            participant(UUID.randomUUID()),
            participant(UUID.randomUUID())
        );
        scheduledFuture = mock(ScheduledFuture.class);
        service = new FetchObjectGameService(
            roomRepository,
            missionRepository,
            fetchRedis,
            gameEventPublisher,
            fetchEventPublisher,
            gameScoreService,
            taskScheduler,
            applicationEventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void startsFetchSessionWithUniqueMissionsAndServerTimestamp() {
        stubPlayableSession(10);

        service.startSession(ROOM_ID, GAME_ID, participants, 10);

        verify(gameEventPublisher).publishStarted(
            ROOM_ID,
            GAME_ID,
            SESSION_SEQ,
            10
        );
        List<Long> missionOrder = initializedMissionOrder.get();
        assertThat(missionOrder).hasSize(10).doesNotHaveDuplicates();

        ArgumentCaptor<Object> payloadCaptor =
            ArgumentCaptor.forClass(Object.class);
        verify(fetchEventPublisher).publish(
            eq(ROOM_ID),
            eq("round:start"),
            payloadCaptor.capture()
        );
        FetchRoundStartedPayload payload =
            (FetchRoundStartedPayload) payloadCaptor.getValue();
        assertThat(payload.round()).isEqualTo(1);
        assertThat(payload.totalRounds()).isEqualTo(10);
        assertThat(payload.target())
            .isIn(FetchObjectMissionCatalog.KEYWORDS);
        assertThat(payload.startedAt()).isEqualTo(NOW.toEpochMilli());

        verify(taskScheduler).schedule(
            any(Runnable.class),
            eq(NOW.plus(FetchObjectGameService.ROUND_DURATION))
        );
    }

    @Test
    void advancesOnTimeoutAndFinishesAfterLastRound() {
        stubPlayableSession(2);
        when(fetchRedis.closeRoundIfPlaying(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt(),
            any(Instant.class),
            any(Instant.class)
        )).thenReturn(true);
        Set<UUID> participantIds = Set.of(
            participants.get(0).participantId(),
            participants.get(1).participantId()
        );
        when(fetchRedis.getParticipants(ROOM_CODE, SESSION_SEQ))
            .thenReturn(participantIds);
        when(fetchRedis.getSubmissionOrder(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt()
        )).thenReturn(List.of());
        when(gameScoreService.saveRoundScores(
            eq(ROOM_ID),
            eq(SESSION_SEQ),
            anyInt(),
            anyMap()
        )).thenReturn(SaveRoundResult.SUCCESS);
        when(gameScoreService.getSessionTotals(ROOM_ID, SESSION_SEQ))
            .thenReturn(Map.of());

        service.startSession(ROOM_ID, GAME_ID, participants, 2);
        scheduledTasks.get(0).run();
        scheduledTasks.get(1).run();

        ArgumentCaptor<String> eventNameCaptor =
            ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor =
            ArgumentCaptor.forClass(Object.class);
        verify(fetchEventPublisher, times(5)).publish(
            eq(ROOM_ID),
            eventNameCaptor.capture(),
            payloadCaptor.capture()
        );
        assertThat(eventNameCaptor.getAllValues()).containsExactly(
            "round:start",
            "round:end",
            "round:start",
            "round:end",
            "game:end"
        );
        FetchGameEndedPayload gameEnded =
            (FetchGameEndedPayload) payloadCaptor.getAllValues().get(4);
        assertThat(gameEnded.scores())
            .hasSize(2)
            .allSatisfy(score -> {
                assertThat(score.score()).isZero();
                assertThat(score.rank()).isEqualTo(1);
            });

        verify(fetchRedis).markSessionEnded(ROOM_CODE, SESSION_SEQ);
        ArgumentCaptor<Object> internalEventCaptor =
            ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(
            internalEventCaptor.capture()
        );
        assertThat(internalEventCaptor.getValue()).isEqualTo(
            new GameSessionFinishedEvent(ROOM_ID, SESSION_SEQ)
        );
        verify(gameScoreService, times(2)).saveRoundScores(
            eq(ROOM_ID),
            eq(SESSION_SEQ),
            anyInt(),
            anyMap()
        );
    }

    @Test
    void acceptsSubmissionAndPublishesServerAssignedRank() {
        UUID participantId = participants.get(0).participantId();
        when(fetchRedis.claimSubmission(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            participantId,
            NOW
        )).thenReturn(new FetchSubmissionClaimResult(
            FetchSubmissionStatus.SUCCESS,
            1,
            1,
            2,
            NOW.plusSeconds(23).toEpochMilli()
        ));
        when(fetchRedis.scoreForRank(1)).thenReturn(5L);
        // 첫 정답 → 그레이스 단축이 타임아웃 타이머를 재예약한다.
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
            .thenAnswer(invocation -> scheduledFuture);

        FetchSubmissionResponse response = service.submit(
            room,
            participantId,
            new FetchSubmissionRequest(1, 0.91, 0.87)
        );

        assertThat(response).isEqualTo(
            new FetchSubmissionResponse(1, participantId, 1, 5L)
        );
        // 첫 정답(아직 전원 제출 아님) → 마감을 그레이스(5초)로 단축하고 새 마감을 이벤트에 싣는다.
        verify(fetchRedis).shortenRoundDeadline(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            NOW.plus(FetchObjectGameService.FIRST_SUBMISSION_GRACE)
        );
        verify(fetchEventPublisher).publish(
            ROOM_ID,
            "round:success",
            new FetchRoundSuccessPayload(
                1,
                participantId,
                1,
                5L,
                NOW.toEpochMilli(),
                NOW.plus(FetchObjectGameService.FIRST_SUBMISSION_GRACE).toEpochMilli()
            )
        );
    }

    @Test
    void returnsRecoverableStateIncludingCurrentRoundScores() {
        UUID first = participants.get(0).participantId();
        UUID second = participants.get(1).participantId();
        Instant submittedAt = NOW.plusSeconds(4);
        when(fetchRedis.findCurrentRoundState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new FetchObjectRoundState(
                2,
                5,
                "마우스",
                NOW,
                NOW.plusSeconds(8),
                "PLAYING",
                List.of(new FetchObjectSubmissionRecord(first, 1, submittedAt))
            )));
        when(fetchRedis.getParticipants(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Set.of(first, second));
        when(fetchRedis.scoreForRank(1)).thenReturn(5L);
        when(gameScoreService.getSessionTotals(ROOM_ID, SESSION_SEQ))
            .thenReturn(Map.of(first, 4L, second, 5L));

        FetchObjectStateResponse response = service.getState(room);

        assertThat(response.round()).isEqualTo(2);
        assertThat(response.target()).isEqualTo("마우스");
        assertThat(response.deadlineAt()).isEqualTo(NOW.plusSeconds(8).toEpochMilli());
        assertThat(response.successes()).singleElement().satisfies(success -> {
            assertThat(success.participantId()).isEqualTo(first);
            assertThat(success.rank()).isEqualTo(1);
            assertThat(success.score()).isEqualTo(5L);
            assertThat(success.submittedAt()).isEqualTo(submittedAt.toEpochMilli());
        });
        assertThat(response.totals())
            .anySatisfy(score -> {
                assertThat(score.participantId()).isEqualTo(first);
                assertThat(score.score()).isEqualTo(9L);
                assertThat(score.rank()).isEqualTo(1);
            })
            .anySatisfy(score -> {
                assertThat(score.participantId()).isEqualTo(second);
                assertThat(score.score()).isEqualTo(5L);
                assertThat(score.rank()).isEqualTo(2);
            });
        verify(taskScheduler).schedule(
            any(Runnable.class),
            eq(NOW.plusSeconds(8))
        );
    }

    @Test
    void lastSubmissionEndsRoundAndPersistsRankingScores() {
        UUID first = participants.get(0).participantId();
        UUID second = participants.get(1).participantId();
        when(fetchRedis.claimSubmission(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            second,
            NOW
        )).thenReturn(new FetchSubmissionClaimResult(
            FetchSubmissionStatus.SUCCESS,
            2,
            2,
            2,
            NOW.plusSeconds(23).toEpochMilli()
        ));
        when(fetchRedis.scoreForRank(1)).thenReturn(5L);
        when(fetchRedis.scoreForRank(2)).thenReturn(4L);
        when(fetchRedis.getTotalRounds(ROOM_CODE, SESSION_SEQ))
            .thenReturn(1);
        when(fetchRedis.closeRoundIfPlaying(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            NOW.plusSeconds(23),
            NOW
        )).thenReturn(true);
        when(fetchRedis.getParticipants(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Set.of(first, second));
        when(fetchRedis.getSubmissionOrder(ROOM_CODE, SESSION_SEQ, 1))
            .thenReturn(List.of(first, second));
        when(gameScoreService.saveRoundScores(
            eq(ROOM_ID),
            eq(SESSION_SEQ),
            eq(1),
            anyMap()
        )).thenReturn(SaveRoundResult.SUCCESS);
        when(gameScoreService.getSessionTotals(ROOM_ID, SESSION_SEQ))
            .thenReturn(Map.of(first, 5L, second, 4L));

        service.submit(
            room,
            second,
            new FetchSubmissionRequest(1, null, null)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Long>> scoresCaptor =
            ArgumentCaptor.forClass(Map.class);
        verify(gameScoreService).saveRoundScores(
            eq(ROOM_ID),
            eq(SESSION_SEQ),
            eq(1),
            scoresCaptor.capture()
        );
        assertThat(scoresCaptor.getValue())
            .containsEntry(first, 5L)
            .containsEntry(second, 4L);
        verify(fetchRedis).markSessionEnded(ROOM_CODE, SESSION_SEQ);
    }

    private void stubPlayableSession(int totalRounds) {
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        List<Mission> missions = missions();
        when(missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                GAME_ID,
                FetchObjectMissionCatalog.MISSION_TYPE
            ))
            .thenReturn(missions);
        when(missionRepository.findById(any(Long.class)))
            .thenAnswer(invocation -> Optional.ofNullable(
                missionById.get(invocation.getArgument(0))
            ));

        doAnswer(invocation -> {
            initializedMissionOrder.set(List.copyOf(invocation.getArgument(5)));
            return null;
        }).when(fetchRedis).initialize(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(GAME_ID),
            eq(totalRounds),
            anyList(),
            anyList()
        );
        when(fetchRedis.getMissionIdAt(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt()
        )).thenAnswer(invocation -> {
            int round = invocation.getArgument(2);
            return initializedMissionOrder.get().get(round - 1);
        });
        when(taskScheduler.schedule(
            any(Runnable.class),
            any(Instant.class)
        )).thenAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(0));
            return scheduledFuture;
        });
    }

    private List<Mission> missions() {
        Game game = Game.builder()
            .gameId(GAME_ID)
            .name("FETCH_OBJECT")
            .description("fetch")
            .minPlayers(2)
            .maxPlayers(4)
            .maxRounds(10)
            .isActive(true)
            .build();
        List<Mission> missions = new ArrayList<>();
        for (int index = 0;
             index < FetchObjectMissionCatalog.KEYWORDS.size();
             index++) {
            Mission mission = Mission.builder()
                .missionId((long) index + 1)
                .game(game)
                .missionType(FetchObjectMissionCatalog.MISSION_TYPE)
                .keyword(FetchObjectMissionCatalog.KEYWORDS.get(index))
                .difficulty("NORMAL")
                .isActive(true)
                .build();
            missions.add(mission);
            missionById.put(mission.getMissionId(), mission);
        }
        return List.copyOf(missions);
    }

    private static Participant participant(UUID participantId) {
        return new Participant(
            participantId,
            "nick-" + participantId.toString().substring(0, 4),
            true,
            ConnectionStatus.CONNECTED,
            NOW
        );
    }
}
