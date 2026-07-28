package com.camon.domain.game.charades.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.repository.CharadesRedisRepository;
import com.camon.domain.game.charades.ws.CharadesEventPublisher;
import com.camon.domain.game.charades.ws.payload.CharadesAnswerRevealedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesGameEndedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundInvalidatedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundScoredPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundStartedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesRoundTimeoutPayload;
import com.camon.domain.game.charades.ws.payload.CharadesTurnStartedPayload;
import com.camon.domain.game.charades.ws.payload.ChatMessageReceivedPayload;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class CharadesGameServiceTest {

    private static final Long GAME_ID = 3L;
    private static final Long TOPIC_ID = 7L;
    private static final int SESSION_SEQ = 1;
    private static final String ROOM_CODE = "CH4R4D";

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private MissionTopicRepository missionTopicRepository;
    @Mock
    private MissionRepository missionRepository;
    @Mock
    private CharadesRedisRepository charadesRedis;
    @Mock
    private GameScoreService gameScoreService;
    @Mock
    private GameEventPublisher gameEventPublisher;
    @Mock
    private CharadesEventPublisher charadesEventPublisher;
    @Mock
    private TaskScheduler taskScheduler;

    private final CharadesAnswerMatcher answerMatcher =
        new CharadesAnswerMatcher();
    private CharadesGameService service;
    private UUID roomId;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new CharadesGameService(
            roomRepository,
            participantRepository,
            missionTopicRepository,
            missionRepository,
            charadesRedis,
            answerMatcher,
            gameScoreService,
            gameEventPublisher,
            charadesEventPublisher,
            taskScheduler
        );
        lenient().when(gameScoreService.saveRoundScores(
            any(), anyInt(), anyInt(), any()
        )).thenReturn(SaveRoundResult.SUCCESS);
        lenient().when(gameScoreService.getSessionTotals(any(), anyInt()))
            .thenReturn(Map.of());
        roomId = UUID.randomUUID();
        room = new Room(
            roomId,
            ROOM_CODE,
            UUID.randomUUID(),
            4,
            RoomStatus.PLAYING,
            SESSION_SEQ,
            Instant.parse("2026-07-27T00:00:00Z")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsSessionWithConnectedParticipantsInJoinOrder() {
        List<Participant> participants = participants(3);
        stubValidStart(participants);
        Instant beforeStart = Instant.now();

        CharadesTurnStartedPayload result = service.startSession(
            roomId,
            GAME_ID,
            TOPIC_ID
        );

        ArgumentCaptor<List<UUID>> orderCaptor = ArgumentCaptor.forClass(List.class);
        verify(charadesRedis).initialize(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(1),
            eq(TOPIC_ID),
            orderCaptor.capture()
        );
        assertThat(orderCaptor.getValue()).containsExactly(
            participants.get(0).participantId(),
            participants.get(1).participantId(),
            participants.get(2).participantId()
        );
        verify(charadesRedis).openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(1),
            eq(1),
            eq(participants.getFirst().participantId()),
            eq(42L),
            any(Instant.class)
        );
        verify(gameEventPublisher).publishStarted(
            roomId,
            GAME_ID,
            SESSION_SEQ,
            1
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:turn-started"),
            any(CharadesTurnStartedPayload.class)
        );
        assertThat(result.round()).isEqualTo(1);
        assertThat(result.turn()).isEqualTo(1);
        assertThat(result.totalTurnsInRound()).isEqualTo(3);
        assertThat(result.presenterId())
            .isEqualTo(participants.getFirst().participantId());
        assertThat(result.expiresAt())
            .isBetween(
                beforeStart.plusSeconds(59),
                Instant.now().plusSeconds(61)
            );
    }

    @Test
    void rejectsInvalidPlayerCount() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findAll(roomId))
            .thenReturn(participants(2));

        assertBusinessError(
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID),
            ErrorCode.CHARADES_NOT_ENOUGH_PLAYERS
        );
        verify(charadesRedis, never()).initialize(
            any(), anyInt(), anyInt(), any(Long.class), anyList()
        );
    }

    @Test
    void rejectsTopicThatDoesNotBelongToCurrentGame() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findAll(roomId))
            .thenReturn(participants(3));
        when(missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(TOPIC_ID, GAME_ID))
            .thenReturn(false);

        assertBusinessError(
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID),
            ErrorCode.CHARADES_TOPIC_NOT_FOUND
        );
    }

    @Test
    void rejectsTopicWhenMissionCountCannotCoverEveryTurn() {
        List<Participant> participants = participants(3);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findAll(roomId)).thenReturn(participants);
        when(missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(TOPIC_ID, GAME_ID))
            .thenReturn(true);
        when(missionRepository
            .findAllByGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                GAME_ID,
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(missions(2));

        assertBusinessError(
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID),
            ErrorCode.CHARADES_NOT_ENOUGH_MISSIONS
        );
        verify(charadesRedis, never()).initialize(
            any(), anyInt(), anyInt(), any(Long.class), anyList()
        );
    }

    @Test
    void skipsDisconnectedPresenterWhenStartingNextTurn() {
        List<Participant> participants = participants(3);
        UUID first = participants.get(0).participantId();
        UUID second = participants.get(1).participantId();
        UUID third = participants.get(2).participantId();
        stubExistingSession(
            new CharadesGameState(
                1, 3, 1, 3, TOPIC_ID, first, 41L,
                Instant.now(), CharadesTurnStatus.CORRECT
            ),
            List.of(first, second, third)
        );
        when(participantRepository.findById(roomId, second))
            .thenReturn(Optional.of(disconnected(second)));
        when(participantRepository.findById(roomId, third))
            .thenReturn(Optional.of(participants.get(2)));

        Optional<CharadesTurnStartedPayload> result =
            service.startNextTurn(roomId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().round()).isEqualTo(1);
        assertThat(result.orElseThrow().turn()).isEqualTo(3);
        assertThat(result.orElseThrow().presenterId()).isEqualTo(third);
        verify(charadesRedis).openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(1),
            eq(3),
            eq(third),
            eq(42L),
            any(Instant.class)
        );
    }

    @Test
    void completesGameAfterEveryParticipantPresentsOnce() {
        List<Participant> participants = participants(3);
        UUID first = participants.get(0).participantId();
        UUID second = participants.get(1).participantId();
        UUID third = participants.get(2).participantId();
        CharadesGameState completedRound = new CharadesGameState(
            1, 1, 3, 3, TOPIC_ID, third, 41L,
            Instant.now(), CharadesTurnStatus.CORRECT
        );
        List<UUID> presenterOrder = participants.stream()
            .map(Participant::participantId)
            .toList();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(completedRound));
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(presenterOrder);
        when(charadesRedis.getRoundScores(ROOM_CODE, SESSION_SEQ, 1))
            .thenReturn(Map.of(first, 2L, second, 1L, third, 1L));
        when(gameScoreService.getSessionTotals(roomId, SESSION_SEQ))
            .thenReturn(Map.of(first, 2L, second, 1L, third, 1L));

        Optional<CharadesTurnStartedPayload> result =
            service.startNextTurn(roomId);

        assertThat(result).isEmpty();
        verify(charadesRedis, never()).openTurn(
            any(), anyInt(), anyInt(), anyInt(),
            any(), any(Long.class), any(Instant.class)
        );
        verify(gameScoreService).saveRoundScores(
            roomId,
            SESSION_SEQ,
            1,
            Map.of(first, 2L, second, 1L, third, 1L)
        );
        ArgumentCaptor<CharadesRoundScoredPayload> scoreCaptor =
            ArgumentCaptor.forClass(CharadesRoundScoredPayload.class);
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:round-scored"),
            scoreCaptor.capture()
        );
        assertThat(scoreCaptor.getValue().scores())
            .extracting("participantId", "roundScore", "totalScore", "rank")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(first, 2L, 2L, 1),
                org.assertj.core.groups.Tuple.tuple(second, 1L, 1L, 2),
                org.assertj.core.groups.Tuple.tuple(third, 1L, 1L, 2)
            );
    }

    @Test
    void doesNotAdvanceWhileCurrentTurnIsPlaying() {
        UUID presenterId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new CharadesGameState(
                1, 3, 1, 3, TOPIC_ID, presenterId, 41L,
                Instant.now(), CharadesTurnStatus.PLAYING
            )));

        assertBusinessError(
            () -> service.startNextTurn(roomId),
            ErrorCode.CHARADES_TURN_STILL_PLAYING
        );
        verify(charadesRedis, never()).openTurn(
            any(), anyInt(), anyInt(), anyInt(),
            any(), any(Long.class), any(Instant.class)
        );
    }

    @Test
    void returnsCurrentWordOnlyForPresenter() {
        UUID presenterId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);
        stubWordState(presenterId, expiresAt, CharadesTurnStatus.PLAYING);
        when(missionRepository
            .findByMissionIdAndGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                42L,
                GAME_ID,
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(Optional.of(mission(42L)));

        var response = service.getCurrentWord(
            roomId,
            GAME_ID,
            presenterId
        );

        assertThat(response.round()).isEqualTo(2);
        assertThat(response.turn()).isEqualTo(3);
        assertThat(response.word()).isEqualTo("제시어42");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsWordRequestFromNonPresenter() {
        UUID presenterId = UUID.randomUUID();
        stubWordState(
            presenterId,
            Instant.now().plusSeconds(60),
            CharadesTurnStatus.PLAYING
        );

        assertBusinessError(
            () -> service.getCurrentWord(
                roomId,
                GAME_ID,
                UUID.randomUUID()
            ),
            ErrorCode.CHARADES_NOT_PRESENTER
        );
        verify(missionRepository, never())
            .findByMissionIdAndGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                any(), any(), any(), any()
            );
    }

    @Test
    void rejectsWordRequestAfterTurnExpires() {
        UUID presenterId = UUID.randomUUID();
        stubWordState(
            presenterId,
            Instant.now().minusSeconds(1),
            CharadesTurnStatus.PLAYING
        );

        assertBusinessError(
            () -> service.getCurrentWord(
                roomId,
                GAME_ID,
                presenterId
            ),
            ErrorCode.CHARADES_TURN_EXPIRED
        );
    }

    @Test
    void rejectsWordRequestWhenTurnIsNotPlaying() {
        UUID presenterId = UUID.randomUUID();
        stubWordState(
            presenterId,
            Instant.now().plusSeconds(60),
            CharadesTurnStatus.CORRECT
        );

        assertBusinessError(
            () -> service.getCurrentWord(
                roomId,
                GAME_ID,
                presenterId
            ),
            ErrorCode.CHARADES_TURN_NOT_PLAYING
        );
    }

    @Test
    void rejectsWordRequestForDifferentGame() {
        UUID presenterId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new CharadesGameState(
                2, 3, 3, 3, TOPIC_ID, presenterId, 42L,
                Instant.now().plusSeconds(60), CharadesTurnStatus.PLAYING
            )));
        when(missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(TOPIC_ID, GAME_ID))
            .thenReturn(false);

        assertBusinessError(
            () -> service.getCurrentWord(
                roomId,
                GAME_ID,
                presenterId
            ),
            ErrorCode.GAME_NOT_CURRENT
        );
    }

    @Test
    void broadcastsWrongGuessAsChatWithoutClosingTurn() {
        UUID presenterId = UUID.randomUUID();
        Participant guesser = participant(UUID.randomUUID(), "정답도전자");
        stubGuessState(presenterId, "축구", Instant.now().plusSeconds(60));

        var response = service.submitGuess(
            roomId, GAME_ID, guesser, new CharadesGuessRequest("농구")
        );

        assertThat(response.round()).isEqualTo(2);
        assertThat(response.turn()).isEqualTo(3);
        assertThat(response.correct()).isFalse();
        verify(charadesRedis, never()).claimCorrectAnswer(
            any(), anyInt(), any(), any()
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("chat:message-received"),
            any(ChatMessageReceivedPayload.class)
        );
        verify(charadesEventPublisher, never()).publish(
            eq(roomId),
            eq("charades:answer-revealed"),
            any(CharadesAnswerRevealedPayload.class)
        );
    }

    @Test
    void firstMatchingGuessClaimsAnswerAndRevealsWinner() {
        UUID presenterId = UUID.randomUUID();
        Participant guesser = participant(UUID.randomUUID(), "정답도전자");
        stubGuessState(
            presenterId, "babyshark", Instant.now().plusSeconds(60)
        );
        stubNextTurnAfterCorrect(presenterId, guesser);
        when(charadesRedis.claimCorrectAnswer(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(guesser.participantId()),
            any(Instant.class)
        )).thenReturn(true);

        var response = service.submitGuess(
            roomId, GAME_ID, guesser, new CharadesGuessRequest("BABY SHARK")
        );

        assertThat(response.correct()).isTrue();
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("chat:message-received"),
            any(ChatMessageReceivedPayload.class)
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:answer-revealed"),
            any(CharadesAnswerRevealedPayload.class)
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:round-started"),
            eq(new CharadesRoundStartedPayload(3, 3, 3))
        );
        verify(charadesRedis).openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(3),
            eq(1),
            eq(guesser.participantId()),
            eq(43L),
            any(Instant.class)
        );
    }

    @Test
    void matchingGuessThatLosesAtomicClaimIsNotAccepted() {
        UUID presenterId = UUID.randomUUID();
        Participant guesser = participant(UUID.randomUUID(), "늦은도전자");
        stubGuessState(presenterId, "축구", Instant.now().plusSeconds(60));
        when(charadesRedis.claimCorrectAnswer(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(guesser.participantId()),
            any(Instant.class)
        )).thenReturn(false);

        var response = service.submitGuess(
            roomId, GAME_ID, guesser, new CharadesGuessRequest("축 구")
        );

        assertThat(response.correct()).isFalse();
        verify(charadesEventPublisher, never()).publish(
            eq(roomId),
            eq("charades:answer-revealed"),
            any(CharadesAnswerRevealedPayload.class)
        );
    }

    @Test
    void visibleSpecialCharacterMakesGuessIncorrect() {
        UUID presenterId = UUID.randomUUID();
        Participant guesser = participant(UUID.randomUUID(), "정답도전자");
        stubGuessState(presenterId, "축구", Instant.now().plusSeconds(60));

        var response = service.submitGuess(
            roomId, GAME_ID, guesser, new CharadesGuessRequest("축구!")
        );

        assertThat(response.correct()).isFalse();
        verify(charadesRedis, never()).claimCorrectAnswer(
            any(), anyInt(), any(), any()
        );
    }

    @Test
    void presenterCannotSubmitGuess() {
        UUID presenterId = UUID.randomUUID();
        Participant presenter = participant(presenterId, "표현자");
        stubWordState(
            presenterId,
            Instant.now().plusSeconds(60),
            CharadesTurnStatus.PLAYING
        );

        assertBusinessError(
            () -> service.submitGuess(
                roomId,
                GAME_ID,
                presenter,
                new CharadesGuessRequest("축구")
            ),
            ErrorCode.CHARADES_PRESENTER_CANNOT_GUESS
        );
        verify(charadesEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void rejectsGuessAfterTurnExpires() {
        UUID presenterId = UUID.randomUUID();
        Participant guesser = participant(UUID.randomUUID(), "정답도전자");
        stubWordState(
            presenterId,
            Instant.now().minusSeconds(1),
            CharadesTurnStatus.PLAYING
        );

        assertBusinessError(
            () -> service.submitGuess(
                roomId,
                GAME_ID,
                guesser,
                new CharadesGuessRequest("축구")
            ),
            ErrorCode.CHARADES_TURN_EXPIRED
        );
        verify(charadesEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void timeoutEndsOnlyCurrentTurnAndStartsNextPresenterInSameRound() {
        UUID presenterId = UUID.randomUUID();
        Participant nextPresenter =
            participant(UUID.randomUUID(), "다음표현자");
        UUID lastPresenterId = UUID.randomUUID();
        Instant scheduledExpiresAt =
            Instant.parse("2026-07-27T12:00:00.123456789Z");
        Instant redisExpiresAt = Instant.ofEpochMilli(
            scheduledExpiresAt.toEpochMilli()
        );
        CharadesGameState playing = new CharadesGameState(
            1, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            redisExpiresAt, CharadesTurnStatus.PLAYING
        );
        CharadesGameState timeout = new CharadesGameState(
            1, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            redisExpiresAt, CharadesTurnStatus.TIMEOUT
        );
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(
                Optional.of(playing),
                Optional.of(timeout),
                Optional.of(timeout)
            );
        when(charadesRedis.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.TIMEOUT
        )).thenReturn(true);
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(List.of(
                presenterId,
                nextPresenter.participantId(),
                lastPresenterId
            ));
        when(participantRepository.findById(
            roomId,
            nextPresenter.participantId()
        )).thenReturn(Optional.of(nextPresenter));
        stubNextMission(43L, "코끼리");

        service.handleTimeout(
            room,
            SESSION_SEQ,
            1,
            1,
            scheduledExpiresAt
        );

        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:round-timeout"),
            eq(new CharadesRoundTimeoutPayload(1, 1))
        );
        verify(charadesRedis).openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(1),
            eq(2),
            eq(nextPresenter.participantId()),
            eq(43L),
            any(Instant.class)
        );
        verify(charadesEventPublisher, never()).publish(
            eq(roomId),
            eq("charades:round-started"),
            any(CharadesRoundStartedPayload.class)
        );
    }

    @Test
    void lastTurnTimeoutFinishesGame() {
        UUID presenterId = UUID.randomUUID();
        Instant expiresAt = Instant.now().minusSeconds(1);
        CharadesGameState playing = new CharadesGameState(
            3, 3, 3, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.PLAYING
        );
        CharadesGameState timeout = new CharadesGameState(
            3, 3, 3, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.TIMEOUT
        );
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(
                Optional.of(playing),
                Optional.of(timeout),
                Optional.of(timeout)
            );
        when(charadesRedis.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.TIMEOUT
        )).thenReturn(true);
        when(charadesRedis.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.TIMEOUT,
            CharadesTurnStatus.FINISHED
        )).thenReturn(true);
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(List.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                presenterId
            ));

        service.handleTimeout(
            room,
            SESSION_SEQ,
            3,
            3,
            expiresAt
        );

        verify(charadesRedis).transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.TIMEOUT,
            CharadesTurnStatus.FINISHED
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:game-ended"),
            any(CharadesGameEndedPayload.class)
        );
        verify(charadesRedis, never()).openTurn(
            any(), anyInt(), anyInt(), anyInt(),
            any(), anyLong(), any()
        );
    }

    @Test
    void forcedPresenterLeaveInvalidatesTurnAndStartsNextPresenter() {
        UUID presenterId = UUID.randomUUID();
        Participant nextPresenter =
            participant(UUID.randomUUID(), "다음표현자");
        Participant remainingParticipant =
            participant(UUID.randomUUID(), "남은참가자");
        Instant expiresAt = Instant.now().plusSeconds(30);
        CharadesGameState playing = new CharadesGameState(
            1, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.PLAYING
        );
        CharadesGameState invalidated = new CharadesGameState(
            1, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.INVALIDATED
        );
        CharadesGameState nextTurn = new CharadesGameState(
            1, 3, 2, 3, TOPIC_ID, nextPresenter.participantId(), 43L,
            Instant.now().plusSeconds(60), CharadesTurnStatus.PLAYING
        );
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(
                Optional.of(playing),
                Optional.of(invalidated),
                Optional.of(invalidated),
                Optional.of(nextTurn)
            );
        when(charadesRedis.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.INVALIDATED
        )).thenReturn(true);
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(List.of(
                presenterId,
                nextPresenter.participantId(),
                remainingParticipant.participantId()
            ));
        when(participantRepository.findAll(roomId)).thenReturn(
            List.of(nextPresenter, remainingParticipant)
        );
        when(participantRepository.findById(
            roomId,
            nextPresenter.participantId()
        )).thenReturn(Optional.of(nextPresenter));
        stubNextMission(43L, "코끼리");
        Instant beforeRestart = Instant.now();
        ArgumentCaptor<Instant> expiresAtCaptor =
            ArgumentCaptor.forClass(Instant.class);

        service.handleParticipantLeft(
            roomId,
            presenterId,
            "TIMEOUT"
        );
        service.handleParticipantLeft(
            roomId,
            presenterId,
            "TIMEOUT"
        );

        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:round-invalidated"),
            eq(new CharadesRoundInvalidatedPayload(
                1,
                1,
                presenterId,
                "TIMEOUT"
            ))
        );
        verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:round-started"),
            eq(new CharadesRoundStartedPayload(1, 3, 3))
        );
        verify(charadesRedis).openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(1),
            eq(2),
            eq(nextPresenter.participantId()),
            eq(43L),
            expiresAtCaptor.capture()
        );
        assertThat(expiresAtCaptor.getValue()).isBetween(
            beforeRestart.plusSeconds(59),
            Instant.now().plusSeconds(61)
        );
        assertThat(expiresAtCaptor.getValue()).isNotEqualTo(expiresAt);
    }

    @Test
    void keepsGameWhenTwoConnectedParticipantsRemain() {
        UUID presenterId = UUID.randomUUID();
        UUID departedParticipantId = UUID.randomUUID();
        CharadesGameState playing = new CharadesGameState(
            1, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            Instant.now().plusSeconds(30),
            CharadesTurnStatus.PLAYING
        );
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(playing));
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            participant(presenterId, "표현자"),
            participant(UUID.randomUUID(), "남은참가자")
        ));

        service.handleParticipantLeft(
            roomId,
            departedParticipantId,
            "LEFT"
        );

        verify(charadesRedis, never()).transitionStatus(
            any(),
            anyInt(),
            any(CharadesTurnStatus.class),
            any(CharadesTurnStatus.class)
        );
        verify(charadesEventPublisher, never()).publish(
            eq(roomId),
            eq("charades:game-ended"),
            any(CharadesGameEndedPayload.class)
        );
        verify(charadesRedis, never()).clear(any(), anyInt());
    }

    @Test
    void finishesGameOnceWhenOnlyOneConnectedParticipantRemains() {
        UUID presenterId = UUID.randomUUID();
        UUID departedParticipantId = UUID.randomUUID();
        CharadesGameState playing = new CharadesGameState(
            2, 3, 1, 3, TOPIC_ID, presenterId, 42L,
            Instant.now().plusSeconds(30),
            CharadesTurnStatus.PLAYING
        );
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(
                Optional.of(playing),
                Optional.of(playing),
                Optional.empty()
            );
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            participant(presenterId, "마지막참가자")
        ));
        when(charadesRedis.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.FINISHED
        )).thenReturn(true);

        service.handleParticipantLeft(
            roomId,
            departedParticipantId,
            "LEFT"
        );
        service.handleParticipantLeft(
            roomId,
            departedParticipantId,
            "LEFT"
        );

        InOrder order = inOrder(
            charadesRedis,
            charadesEventPublisher
        );
        order.verify(charadesRedis).transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.FINISHED
        );
        order.verify(charadesEventPublisher).publish(
            eq(roomId),
            eq("charades:game-ended"),
            any(CharadesGameEndedPayload.class)
        );
        order.verify(charadesRedis).clear(ROOM_CODE, SESSION_SEQ);
    }

    @Test
    void rejectsGuessAndTurnProgressAfterCharadesDataIsCleared() {
        Participant participant =
            participant(UUID.randomUUID(), "마지막참가자");
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.empty());

        assertBusinessError(
            () -> service.submitGuess(
                roomId,
                GAME_ID,
                participant,
                new CharadesGuessRequest("코끼리")
            ),
            ErrorCode.CHARADES_SESSION_NOT_FOUND
        );
        assertBusinessError(
            () -> service.startNextTurn(roomId),
            ErrorCode.CHARADES_SESSION_NOT_FOUND
        );
    }

    private void stubValidStart(List<Participant> participants) {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findAll(roomId)).thenReturn(participants);
        when(missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(TOPIC_ID, GAME_ID))
            .thenReturn(true);
        when(missionRepository
            .findAllByGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                GAME_ID,
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(missions(participants.size()));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new CharadesGameState(
                0,
                1,
                0,
                participants.size(),
                TOPIC_ID,
                null,
                null,
                null,
                CharadesTurnStatus.READY
            )));
        when(charadesRedis.getUsedMissionIds(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Set.of());
        when(missionRepository
            .findAllByTopicTopicIdAndMissionTypeAndIsActiveTrue(
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(List.of(mission(42L)));
        when(charadesRedis.openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt(),
            anyInt(),
            any(UUID.class),
            any(Long.class),
            any(Instant.class)
        )).thenReturn(true);
    }

    private void stubExistingSession(
        CharadesGameState state,
        List<UUID> presenterOrder
    ) {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(state));
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(presenterOrder);
        when(charadesRedis.getUsedMissionIds(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Set.of(41L));
        when(missionRepository
            .findAllByTopicTopicIdAndMissionTypeAndIsActiveTrue(
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(List.of(mission(42L)));
        when(charadesRedis.openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt(),
            anyInt(),
            any(UUID.class),
            any(Long.class),
            any(Instant.class)
        )).thenReturn(true);
    }

    private void stubWordState(
        UUID presenterId,
        Instant expiresAt,
        CharadesTurnStatus status
    ) {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new CharadesGameState(
                2,
                3,
                3,
                3,
                TOPIC_ID,
                presenterId,
                42L,
                expiresAt,
                status
            )));
        when(missionTopicRepository
            .existsByTopicIdAndGameGameIdAndIsActiveTrue(TOPIC_ID, GAME_ID))
            .thenReturn(true);
    }

    private void stubGuessState(
        UUID presenterId,
        String keyword,
        Instant expiresAt
    ) {
        stubWordState(
            presenterId,
            expiresAt,
            CharadesTurnStatus.PLAYING
        );
        when(missionRepository
            .findByMissionIdAndGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
                42L,
                GAME_ID,
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(Optional.of(missionWithKeyword(42L, keyword)));
    }

    private void stubNextTurnAfterCorrect(
        UUID presenterId,
        Participant nextPresenter
    ) {
        Instant expiresAt = Instant.now().plusSeconds(60);
        CharadesGameState playing = new CharadesGameState(
            2, 3, 3, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.PLAYING
        );
        CharadesGameState correct = new CharadesGameState(
            2, 3, 3, 3, TOPIC_ID, presenterId, 42L,
            expiresAt, CharadesTurnStatus.CORRECT
        );
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(
                Optional.of(playing),
                Optional.of(correct),
                Optional.of(correct)
            );
        when(charadesRedis.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .thenReturn(List.of(
                nextPresenter.participantId(),
                UUID.randomUUID(),
                presenterId
            ));
        when(participantRepository.findById(
            roomId,
            nextPresenter.participantId()
        )).thenReturn(Optional.of(nextPresenter));
        stubNextMission(43L, "코끼리");
    }

    private void stubNextMission(long missionId, String keyword) {
        when(charadesRedis.getUsedMissionIds(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Set.of(42L));
        when(missionRepository
            .findAllByTopicTopicIdAndMissionTypeAndIsActiveTrue(
                TOPIC_ID,
                "CHARADES"
            )).thenReturn(List.of(missionWithKeyword(missionId, keyword)));
        when(charadesRedis.openTurn(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            anyInt(),
            anyInt(),
            any(UUID.class),
            eq(missionId),
            any(Instant.class)
        )).thenReturn(true);
    }

    private static List<Participant> participants(int count) {
        List<Participant> participants = new ArrayList<>();
        Instant joinedAt = Instant.parse("2026-07-27T00:00:00Z");
        for (int index = 0; index < count; index++) {
            participants.add(new Participant(
                UUID.randomUUID(),
                "참가자" + index,
                true,
                ConnectionStatus.CONNECTED,
                joinedAt.plusSeconds(index)
            ));
        }
        return List.copyOf(participants);
    }

    private static Participant disconnected(UUID participantId) {
        return new Participant(
            participantId,
            "연결 끊김",
            true,
            ConnectionStatus.DISCONNECTED,
            Instant.now()
        );
    }

    private static Participant participant(UUID participantId, String nickname) {
        return new Participant(
            participantId,
            nickname,
            true,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
    }

    private static List<Mission> missions(int count) {
        List<Mission> missions = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            missions.add(mission(id));
        }
        return List.copyOf(missions);
    }

    private static Mission missionWithKeyword(long id, String keyword) {
        return Mission.builder()
            .missionId(id)
            .missionType("CHARADES")
            .keyword(keyword)
            .difficulty("NORMAL")
            .isActive(true)
            .build();
    }

    private static Mission mission(long id) {
        return Mission.builder()
            .missionId(id)
            .missionType("CHARADES")
            .keyword("제시어" + id)
            .difficulty("NORMAL")
            .isActive(true)
            .build();
    }

    private static void assertBusinessError(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
        ErrorCode expected
    ) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.errorCode())
                    .isEqualTo(expected)
            );
    }
}
