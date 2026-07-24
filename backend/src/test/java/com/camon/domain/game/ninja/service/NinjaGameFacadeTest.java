package com.camon.domain.game.ninja.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.repository.NinjaRedisRepository;
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
class NinjaGameFacadeTest {

    @Mock
    private NinjaGameService ninjaGameService;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private NinjaRedisRepository ninjaRedisRepository;

    private NinjaGameFacade facade;

    private final UUID roomId = UUID.randomUUID();
    private final UUID participantId = UUID.randomUUID();
    private final Long gameId = 3L;
    private final String roomCode = "ABC123";

    @BeforeEach
    void setUp() {
        facade = new NinjaGameFacade(
            ninjaGameService,
            roomRepository,
            participantRepository,
            ninjaRedisRepository
        );
    }

    @Test
    void attack_resolvesCurrentRoomFromAuthenticatedParticipant() {
        givenCurrentGame(gameId);
        AttackRequest request = new AttackRequest(10L);

        facade.attack(gameId, 1, participantId, request);

        verify(ninjaGameService).attack(
            roomId,
            1,
            participantId.toString(),
            request
        );
    }

    @Test
    void getState_throwsWhenParticipantHasNoCurrentRoom() {
        when(participantRepository.findCurrentRoomId(participantId))
            .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> facade.getState(gameId, participantId)
        );

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.ROOM_ACCESS_DENIED);
    }

    @Test
    void getState_throwsWhenNinjaSessionDoesNotExist() {
        givenRoomMembership();
        when(ninjaRedisRepository.getGameId(roomCode, 1)).thenReturn(null);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> facade.getState(gameId, participantId)
        );

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.NINJA_SESSION_NOT_FOUND);
    }

    @Test
    void getState_throwsWhenRequestedGameIsNotCurrent() {
        givenCurrentGame(2L);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> facade.getState(gameId, participantId)
        );

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.GAME_NOT_CURRENT);
    }

    private void givenCurrentGame(Long currentGameId) {
        givenRoomMembership();
        when(ninjaRedisRepository.getGameId(roomCode, 1))
            .thenReturn(currentGameId);
    }

    private void givenRoomMembership() {
        Room room = new Room(
            roomId,
            roomCode,
            participantId,
            4,
            RoomStatus.PLAYING,
            1,
            Instant.now()
        );
        Participant participant = new Participant(
            participantId,
            "tester",
            true,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
        when(participantRepository.findCurrentRoomId(participantId))
            .thenReturn(Optional.of(roomId));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participantRepository.findById(roomId, participantId))
            .thenReturn(Optional.of(participant));
    }
}
