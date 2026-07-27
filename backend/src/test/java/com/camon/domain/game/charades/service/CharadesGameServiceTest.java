package com.camon.domain.game.charades.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.repository.CharadesRedisRepository;
import com.camon.domain.game.charades.ws.CharadesEventPublisher;
import com.camon.domain.game.charades.ws.payload.CharadesAnswerRevealedPayload;
import com.camon.domain.game.charades.ws.payload.CharadesTurnStartedPayload;
import com.camon.domain.game.charades.ws.payload.ChatMessageReceivedPayload;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private GameEventPublisher gameEventPublisher;
    @Mock
    private CharadesEventPublisher charadesEventPublisher;

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
            gameEventPublisher,
            charadesEventPublisher
        );
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
        stubValidStart(participants, 3);
        Instant beforeStart = Instant.now();

        CharadesTurnStartedPayload result = service.startSession(
            roomId,
            GAME_ID,
            TOPIC_ID,
            3
        );

        ArgumentCaptor<List<UUID>> orderCaptor = ArgumentCaptor.forClass(List.class);
        verify(charadesRedis).initialize(
            eq(ROOM_CODE),
            eq(SESSION_SEQ),
            eq(3),
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
            3
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
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID, 3),
            ErrorCode.CHARADES_NOT_ENOUGH_PLAYERS
        );
        verify(charadesRedis, never()).initialize(
            any(), anyInt(), anyInt(), any(Long.class), anyList()
        );
    }

    @Test
    void rejectsInvalidRoundCountBeforeAccessingRoom() {
        assertBusinessError(
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID, 4),
            ErrorCode.CHARADES_INVALID_ROUND_COUNT
        );
        verify(roomRepository, never()).findById(any());
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
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID, 3),
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
            )).thenReturn(missions(8));

        assertBusinessError(
            () -> service.startSession(roomId, GAME_ID, TOPIC_ID, 3),
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
    void wrapsPresenterOrderAtStartOfNextRound() {
        List<Participant> participants = participants(3);
        UUID first = participants.get(0).participantId();
        UUID third = participants.get(2).participantId();
        stubExistingSession(
            new CharadesGameState(
                1, 3, 3, 3, TOPIC_ID, third, 41L,
                Instant.now(), CharadesTurnStatus.CORRECT
            ),
            participants.stream().map(Participant::participantId).toList()
        );
        when(participantRepository.findById(roomId, first))
            .thenReturn(Optional.of(participants.get(0)));

        CharadesTurnStartedPayload result = service.startNextTurn(roomId)
            .orElseThrow();

        assertThat(result.round()).isEqualTo(2);
        assertThat(result.turn()).isEqualTo(1);
        assertThat(result.presenterId()).isEqualTo(first);
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

    private void stubValidStart(
        List<Participant> participants,
        int totalRounds
    ) {
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
            )).thenReturn(missions(participants.size() * totalRounds));
        when(charadesRedis.findState(ROOM_CODE, SESSION_SEQ))
            .thenReturn(Optional.of(new CharadesGameState(
                0,
                totalRounds,
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
