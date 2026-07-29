package com.camon.domain.game.fetch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FetchObjectGameFacadeTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final String ROOM_CODE = "AB12CD";
    private static final int SESSION_SEQ = 3;
    private static final Long GAME_ID = 2L;

    @Mock
    private FetchObjectGameService fetchObjectGameService;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private FetchObjectRedisRepository fetchRedis;

    private FetchObjectGameFacade facade;
    private Room room;
    private Participant participant;

    @BeforeEach
    void setUp() {
        facade = new FetchObjectGameFacade(
            fetchObjectGameService,
            roomRepository,
            participantRepository,
            fetchRedis
        );
        room = new Room(
            ROOM_ID,
            ROOM_CODE,
            UUID.randomUUID(),
            4,
            RoomStatus.PLAYING,
            SESSION_SEQ,
            Instant.now()
        );
        participant = new Participant(
            PARTICIPANT_ID,
            "guest",
            false,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
    }

    @Test
    void resolvesParticipantRoomAndDelegatesSubmission() {
        FetchSubmissionRequest request =
            new FetchSubmissionRequest(1, 0.9, 0.8);
        FetchSubmissionResponse expected =
            new FetchSubmissionResponse(1, PARTICIPANT_ID, 1, 5L);
        stubParticipantContext();
        when(fetchRedis.getGameId(ROOM_CODE, SESSION_SEQ))
            .thenReturn(GAME_ID);
        when(fetchObjectGameService.submit(room, PARTICIPANT_ID, request))
            .thenReturn(expected);

        FetchSubmissionResponse response = facade.submit(
            GAME_ID,
            PARTICIPANT_ID,
            request
        );

        assertThat(response).isEqualTo(expected);
        verify(fetchObjectGameService).submit(
            room,
            PARTICIPANT_ID,
            request
        );
    }

    @Test
    void rejectsWhenFetchSessionDoesNotExist() {
        stubParticipantContext();
        when(fetchRedis.getGameId(ROOM_CODE, SESSION_SEQ))
            .thenReturn(null);

        assertThatThrownBy(() ->
            facade.submit(
                GAME_ID,
                PARTICIPANT_ID,
                new FetchSubmissionRequest(1, null, null)
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting(error ->
                ((BusinessException) error).errorCode()
            )
            .isEqualTo(ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND);
    }

    @Test
    void rejectsDifferentCurrentGame() {
        stubParticipantContext();
        when(fetchRedis.getGameId(ROOM_CODE, SESSION_SEQ))
            .thenReturn(1L);

        assertThatThrownBy(() ->
            facade.submit(
                GAME_ID,
                PARTICIPANT_ID,
                new FetchSubmissionRequest(1, null, null)
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting(error ->
                ((BusinessException) error).errorCode()
            )
            .isEqualTo(ErrorCode.GAME_NOT_CURRENT);
    }

    private void stubParticipantContext() {
        when(participantRepository.findCurrentRoomId(PARTICIPANT_ID))
            .thenReturn(Optional.of(ROOM_ID));
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(participantRepository.findById(ROOM_ID, PARTICIPANT_ID))
            .thenReturn(Optional.of(participant));
    }
}
