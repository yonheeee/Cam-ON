package com.camon.domain.game.charades.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.charades.dto.CharadesWordResponse;
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
class CharadesGameFacadeTest {

    @Mock
    private CharadesGameService charadesGameService;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantRepository participantRepository;

    private CharadesGameFacade facade;
    private UUID roomId;
    private UUID participantId;
    private Room room;

    @BeforeEach
    void setUp() {
        facade = new CharadesGameFacade(
            charadesGameService,
            roomRepository,
            participantRepository
        );
        roomId = UUID.randomUUID();
        participantId = UUID.randomUUID();
        room = new Room(
            roomId,
            "CH4R4D",
            UUID.randomUUID(),
            4,
            RoomStatus.PLAYING,
            1,
            Instant.now()
        );
    }

    @Test
    void resolvesCurrentRoomAndDelegatesWordRequest() {
        Long gameId = 3L;
        Instant expiresAt = Instant.now().plusSeconds(60);
        Participant participant = new Participant(
            participantId,
            "표현자",
            true,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
        CharadesWordResponse expected = new CharadesWordResponse(
            1,
            1,
            "코끼리",
            expiresAt
        );
        when(participantRepository.findCurrentRoomId(participantId))
            .thenReturn(Optional.of(roomId));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findById(roomId, participantId))
            .thenReturn(Optional.of(participant));
        when(charadesGameService.getCurrentWord(
            roomId,
            gameId,
            participantId
        )).thenReturn(expected);

        assertThat(facade.getCurrentWord(gameId, participantId))
            .isEqualTo(expected);
    }

    @Test
    void rejectsParticipantWithoutCurrentRoom() {
        when(participantRepository.findCurrentRoomId(participantId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            facade.getCurrentWord(3L, participantId)
        ).isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode())
                .isEqualTo(ErrorCode.ROOM_ACCESS_DENIED)
        );
        verify(charadesGameService, never())
            .getCurrentWord(roomId, 3L, participantId);
    }
}
