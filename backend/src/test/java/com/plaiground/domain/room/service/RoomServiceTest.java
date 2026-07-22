package com.plaiground.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.dto.CreateRoomRequest;
import com.plaiground.domain.room.dto.CreateRoomResponse;
import com.plaiground.domain.room.dto.JoinRoomRequest;
import com.plaiground.domain.room.dto.JoinRoomResponse;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.repository.LeaveRoomResult;
import com.plaiground.domain.room.repository.LeaveRoomStatus;
import com.plaiground.domain.room.ws.RoomEventPublisher;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.repository.SessionRepository;
import com.plaiground.global.exception.BusinessException;
import com.plaiground.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoomServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    private RoomRepository roomRepository;
    private ParticipantRepository participantRepository;
    private SessionRepository sessionRepository;
    private RoomCodeGenerator roomCodeGenerator;
    private RoomInviteLinkGenerator inviteLinkGenerator;
    private RoomEventPublisher roomEventPublisher;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        participantRepository = mock(ParticipantRepository.class);
        sessionRepository = mock(SessionRepository.class);
        roomCodeGenerator = mock(RoomCodeGenerator.class);
        inviteLinkGenerator = mock(RoomInviteLinkGenerator.class);
        roomEventPublisher = mock(RoomEventPublisher.class);
        roomService = new RoomService(
            roomRepository,
            participantRepository,
            sessionRepository,
            roomCodeGenerator,
            inviteLinkGenerator,
            roomEventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsRoomAndRegistersCreatorAsHost() {
        UUID participantId = UUID.randomUUID();
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(participantId, "플레이어1", NOW))
        );
        when(roomCodeGenerator.generate()).thenReturn("AB23CD");
        when(roomRepository.tryCreate(any(Room.class), any(Participant.class)))
            .thenReturn(true);
        when(inviteLinkGenerator.generate("AB23CD")).thenReturn(
            "https://plaiground.example/rooms/join?code=AB23CD"
        );

        CreateRoomResponse response = roomService.createRoom(
            participantId,
            new CreateRoomRequest("테스트 방", 4)
        );

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        ArgumentCaptor<Participant> hostCaptor =
            ArgumentCaptor.forClass(Participant.class);
        verify(roomRepository).tryCreate(
            roomCaptor.capture(),
            hostCaptor.capture()
        );
        Room savedRoom = roomCaptor.getValue();
        Participant savedHost = hostCaptor.getValue();
        assertThat(savedRoom.roomCode()).isEqualTo("AB23CD");
        assertThat(savedRoom.hostParticipantId()).isEqualTo(participantId);
        assertThat(savedRoom.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(savedHost.participantId()).isEqualTo(participantId);
        assertThat(savedHost.nickname()).isEqualTo("플레이어1");
        assertThat(savedHost.ready()).isFalse();
        assertThat(savedHost.connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(response.room().roomCode()).isEqualTo("AB23CD");
        assertThat(response.room().participants()).singleElement()
            .satisfies(participant -> assertThat(participant.role()).isEqualTo("HOST"));
        assertThat(response.inviteUrl()).contains("code=AB23CD");
    }

    @Test
    void rejectsMissingGuestSession() {
        UUID participantId = UUID.randomUUID();
        when(sessionRepository.findByParticipantId(participantId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.createRoom(
            participantId,
            new CreateRoomRequest("테스트 방", 4)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)
        );
    }

    @Test
    void retriesWhenRoomCodeCollides() {
        UUID participantId = UUID.randomUUID();
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(participantId, "플레이어1", NOW))
        );
        when(roomCodeGenerator.generate()).thenReturn("AB23CD", "EF45GH");
        when(roomRepository.tryCreate(any(Room.class), any(Participant.class)))
            .thenReturn(false, true);

        CreateRoomResponse response = roomService.createRoom(
            participantId,
            new CreateRoomRequest("테스트 방", 4)
        );

        assertThat(response.room().roomCode()).isEqualTo("EF45GH");
    }

    @Test
    void joinsRoomByCodeAndReturnsCurrentSnapshot() {
        UUID hostId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Room room = new Room(
            UUID.randomUUID(),
            "AB23CD",
            "game room",
            hostId,
            4,
            RoomStatus.WAITING,
            NOW
        );
        Participant host = new Participant(
            hostId,
            "host",
            false,
            ConnectionStatus.CONNECTED,
            NOW.minusSeconds(1)
        );
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(participantId, "guest", NOW))
        );
        when(roomRepository.findByCode("AB23CD")).thenReturn(Optional.of(room));
        when(participantRepository.tryAdd(
            room.roomId(),
            new Participant(
                participantId,
                "guest",
                false,
                ConnectionStatus.CONNECTED,
                NOW
            )
        )).thenReturn(JoinParticipantResult.SUCCESS);
        when(roomRepository.findById(room.roomId())).thenReturn(Optional.of(room));
        when(participantRepository.findAll(room.roomId())).thenReturn(List.of(
            host,
            new Participant(
                participantId,
                "guest",
                false,
                ConnectionStatus.CONNECTED,
                NOW
            )
        ));

        JoinRoomResponse response = roomService.joinRoom(
            participantId,
            new JoinRoomRequest("AB23CD")
        );

        assertThat(response.room().roomId()).isEqualTo(room.roomId());
        assertThat(response.room().participants())
            .extracting(participant -> participant.role())
            .containsExactly("HOST", "MEMBER");
    }

    @Test
    void convertsAtomicJoinFailureToBusinessException() {
        UUID participantId = UUID.randomUUID();
        Room room = new Room(
            UUID.randomUUID(),
            "AB23CD",
            "full room",
            UUID.randomUUID(),
            2,
            RoomStatus.WAITING,
            NOW
        );
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(participantId, "guest", NOW))
        );
        when(roomRepository.findByCode("AB23CD")).thenReturn(Optional.of(room));
        when(participantRepository.tryAdd(any(UUID.class), any(Participant.class)))
            .thenReturn(JoinParticipantResult.ROOM_FULL);

        assertThatThrownBy(() -> roomService.joinRoom(
            participantId,
            new JoinRoomRequest("AB23CD")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.ROOM_FULL)
        );
    }

    @Test
    void returnsRoomSnapshotToParticipant() {
        UUID hostId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Room room = room(hostId);
        Participant host = participant(hostId, "host", NOW.minusSeconds(1));
        Participant member = participant(memberId, "member", NOW);
        when(roomRepository.findById(room.roomId())).thenReturn(Optional.of(room));
        when(participantRepository.findById(room.roomId(), memberId))
            .thenReturn(Optional.of(member));
        when(participantRepository.findAll(room.roomId()))
            .thenReturn(List.of(host, member));

        var response = roomService.getRoom(room.roomId(), memberId);

        assertThat(response.roomId()).isEqualTo(room.roomId());
        assertThat(response.participants())
            .extracting(participant -> participant.role())
            .containsExactly("HOST", "MEMBER");
    }

    @Test
    void rejectsRoomLookupWhenRoomDoesNotExist() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoom(roomId, UUID.randomUUID()))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND)
            );
    }

    @Test
    void rejectsRoomLookupByNonParticipant() {
        UUID participantId = UUID.randomUUID();
        Room room = room(UUID.randomUUID());
        when(roomRepository.findById(room.roomId())).thenReturn(Optional.of(room));
        when(participantRepository.findById(room.roomId(), participantId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoom(room.roomId(), participantId))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.ROOM_ACCESS_DENIED)
            );
    }

    private static Room room(UUID hostId) {
        return new Room(
            UUID.randomUUID(),
            "AB23CD",
            "game room",
            hostId,
            4,
            RoomStatus.WAITING,
            NOW
        );
    }

    private static Participant participant(
        UUID participantId,
        String nickname,
        Instant joinedAt
    ) {
        return new Participant(
            participantId,
            nickname,
            false,
            ConnectionStatus.CONNECTED,
            joinedAt
        );
    }

    @Test
    void publishesLeaveAndHostChangeEventsAfterHostLeaves() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID newHostId = UUID.randomUUID();
        when(roomRepository.leave(roomId, hostId)).thenReturn(
            new LeaveRoomResult(
                LeaveRoomStatus.HOST_CHANGED,
                hostId,
                newHostId
            )
        );

        roomService.leaveRoom(roomId, hostId);

        verify(roomEventPublisher).publishMemberLeft(
            roomId,
            hostId,
            newHostId
        );
        verify(roomEventPublisher).publishHostChanged(
            roomId,
            hostId,
            newHostId
        );
    }

    @Test
    void rejectsLeaveWhenParticipantIsNotInRoom() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(roomRepository.leave(roomId, participantId)).thenReturn(
            new LeaveRoomResult(
                LeaveRoomStatus.PARTICIPANT_NOT_FOUND,
                null,
                null
            )
        );

        assertThatThrownBy(() -> roomService.leaveRoom(roomId, participantId))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.ROOM_ACCESS_DENIED)
            );
    }
}
