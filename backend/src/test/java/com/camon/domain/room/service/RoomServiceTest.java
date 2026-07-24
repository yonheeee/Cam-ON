package com.camon.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.media.service.LiveKitTokenService;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.dto.CreateRoomRequest;
import com.camon.domain.room.dto.CreateRoomResponse;
import com.camon.domain.room.dto.JoinRoomRequest;
import com.camon.domain.room.dto.JoinRoomResponse;
import com.camon.domain.room.dto.UpdateReadyRequest;
import com.camon.domain.room.repository.JoinParticipantResult;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ReadyUpdateResult;
import com.camon.domain.room.repository.ReadyUpdateStatus;
import com.camon.domain.room.ws.RoomEventPublisher;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.domain.session.domain.GuestSession;
import com.camon.domain.session.repository.SessionRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
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
    private LiveKitTokenService liveKitTokenService;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        participantRepository = mock(ParticipantRepository.class);
        sessionRepository = mock(SessionRepository.class);
        roomCodeGenerator = mock(RoomCodeGenerator.class);
        inviteLinkGenerator = mock(RoomInviteLinkGenerator.class);
        roomEventPublisher = mock(RoomEventPublisher.class);
        liveKitTokenService = mock(LiveKitTokenService.class);
        roomService = new RoomService(
            roomRepository,
            participantRepository,
            sessionRepository,
            roomCodeGenerator,
            inviteLinkGenerator,
            roomEventPublisher,
            liveKitTokenService,
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
            "https://camon.example/rooms/join?code=AB23CD"
        );

        CreateRoomResponse response = roomService.createRoom(
            participantId,
            new CreateRoomRequest(4)
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
            new CreateRoomRequest(4)
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
            new CreateRoomRequest(4)
        );

        assertThat(response.room().roomCode()).isEqualTo("EF45GH");
    }

    @Test
    void joinsRoomByIdAndReturnsCurrentSnapshot() {
        UUID hostId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Room room = new Room(
            UUID.randomUUID(),
            "AB23CD",
            hostId,
            4,
            RoomStatus.WAITING,
            1,
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
        when(roomRepository.findByCode(room.roomCode())).thenReturn(Optional.of(room));
        when(roomRepository.findById(room.roomId())).thenReturn(Optional.of(room));
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
            new JoinRoomRequest(room.roomCode())
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
            UUID.randomUUID(),
            2,
            RoomStatus.WAITING,
            1,
            NOW
        );
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(participantId, "guest", NOW))
        );
        when(roomRepository.findByCode(room.roomCode())).thenReturn(Optional.of(room));
        when(participantRepository.tryAdd(any(UUID.class), any(Participant.class)))
            .thenReturn(JoinParticipantResult.ROOM_FULL);

        assertThatThrownBy(() -> roomService.joinRoom(
            participantId,
            new JoinRoomRequest(room.roomCode())
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
            hostId,
            4,
            RoomStatus.WAITING,
            1,
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
    void publishesNewHostInLeaveEventAfterHostLeaves() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID newHostId = UUID.randomUUID();
        when(participantRepository.leave(roomId, hostId)).thenReturn(
            new LeaveRoomResult(
                    LeaveRoomStatus.SUCCESS,
                    hostId,
                    hostId,
                    newHostId,
                    false
            )
        );

        roomService.leaveRoom(roomId, hostId);

        verify(roomEventPublisher).publishMemberLeft(
            roomId,
            hostId,
            newHostId
        );
    }

    @Test
    void rejectsLeaveWhenParticipantIsNotInRoom() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.leave(roomId, participantId)).thenReturn(
            new LeaveRoomResult(
                    LeaveRoomStatus.PARTICIPANT_NOT_FOUND,
                    participantId,
                    null,
                    null,
                    false
            )
        );

        assertThatThrownBy(() -> roomService.leaveRoom(roomId, participantId))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(ErrorCode.ROOM_ACCESS_DENIED)
            );
    }

    @Test
    void updatesReadyAndPublishesAllReadyState() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.updateReady(roomId, participantId, true))
            .thenReturn(new ReadyUpdateResult(
                ReadyUpdateStatus.SUCCESS,
                true,
                true
            ));

        var response = roomService.updateReady(
            roomId,
            participantId,
            new UpdateReadyRequest(true)
        );

        assertThat(response.ready()).isTrue();
        assertThat(response.allReady()).isTrue();
        verify(roomEventPublisher).publishMemberReadyUpdated(
            roomId,
            participantId,
            true,
            true
        );
    }

    @Test
    void rejectsReadyUpdateAfterRoomStarted() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.updateReady(roomId, participantId, true))
            .thenReturn(new ReadyUpdateResult(
                ReadyUpdateStatus.ROOM_ALREADY_STARTED,
                false,
                false
            ));

        assertThatThrownBy(() -> roomService.updateReady(
            roomId,
            participantId,
            new UpdateReadyRequest(true)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(ErrorCode.ROOM_ALREADY_STARTED)
        );
    }
}
