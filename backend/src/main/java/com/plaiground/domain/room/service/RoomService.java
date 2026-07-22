package com.plaiground.domain.room.service;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.dto.CreateRoomRequest;
import com.plaiground.domain.room.dto.CreateRoomResponse;
import com.plaiground.domain.room.dto.JoinRoomRequest;
import com.plaiground.domain.room.dto.JoinRoomResponse;
import com.plaiground.domain.room.dto.ParticipantResponse;
import com.plaiground.domain.room.dto.RoomSnapshotResponse;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.repository.SessionRepository;
import com.plaiground.global.exception.BusinessException;
import com.plaiground.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final SessionRepository sessionRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final RoomInviteLinkGenerator inviteLinkGenerator;
    private final Clock clock;

    public RoomService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        SessionRepository sessionRepository,
        RoomCodeGenerator roomCodeGenerator,
        RoomInviteLinkGenerator inviteLinkGenerator,
        Clock jwtClock
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.sessionRepository = sessionRepository;
        this.roomCodeGenerator = roomCodeGenerator;
        this.inviteLinkGenerator = inviteLinkGenerator;
        this.clock = jwtClock;
    }

    public CreateRoomResponse createRoom(
        UUID participantId,
        CreateRoomRequest request
    ) {
        GuestSession guestSession = sessionRepository
            .findByParticipantId(participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        Instant createdAt = clock.instant();

        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            Room room = new Room(
                UUID.randomUUID(),
                roomCodeGenerator.generate(),
                request.title(),
                participantId,
                request.maxPlayers(),
                RoomStatus.WAITING,
                createdAt
            );
            Participant host = new Participant(
                participantId,
                guestSession.nickname(),
                false,
                ConnectionStatus.CONNECTED,
                createdAt
            );

            if (roomRepository.tryCreate(room, host)) {
                return toCreateRoomResponse(room, host);
            }
        }

        throw new BusinessException(ErrorCode.ROOM_CODE_GENERATION_FAILED);
    }

    public JoinRoomResponse joinRoom(
        UUID participantId,
        JoinRoomRequest request
    ) {
        GuestSession guestSession = sessionRepository
            .findByParticipantId(participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        Room room = roomRepository.findByCode(request.roomCode())
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        Participant participant = new Participant(
            participantId,
            guestSession.nickname(),
            false,
            ConnectionStatus.CONNECTED,
            clock.instant()
        );

        JoinParticipantResult result = participantRepository.tryAdd(
            room.roomId(),
            participant
        );
        if (result != JoinParticipantResult.SUCCESS) {
            throw new BusinessException(toErrorCode(result));
        }

        Room currentRoom = roomRepository.findById(room.roomId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        return new JoinRoomResponse(toRoomSnapshot(currentRoom), null);
    }

    public RoomSnapshotResponse getRoom(UUID roomId, UUID participantId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        participantRepository.findById(roomId, participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_ACCESS_DENIED));
        return toRoomSnapshot(room);
    }

    private CreateRoomResponse toCreateRoomResponse(
        Room room,
        Participant host
    ) {
        return new CreateRoomResponse(
            toRoomSnapshot(room, List.of(host)),
            inviteLinkGenerator.generate(room.roomCode()),
            null
        );
    }

    private RoomSnapshotResponse toRoomSnapshot(Room room) {
        return toRoomSnapshot(room, participantRepository.findAll(room.roomId()));
    }

    private RoomSnapshotResponse toRoomSnapshot(
        Room room,
        List<Participant> participants
    ) {
        List<ParticipantResponse> participantResponses = participants.stream()
            .map(participant -> new ParticipantResponse(
                participant.participantId(),
                participant.nickname(),
                participant.participantId().equals(room.hostParticipantId())
                    ? "HOST"
                    : "MEMBER",
                participant.ready(),
                participant.connectionStatus()
            ))
            .toList();
        return new RoomSnapshotResponse(
            room.roomId(),
            room.roomCode(),
            room.title(),
            room.maxPlayers(),
            room.status(),
            room.hostParticipantId(),
            participantResponses
        );
    }

    private ErrorCode toErrorCode(JoinParticipantResult result) {
        return switch (result) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case ROOM_FULL -> ErrorCode.ROOM_FULL;
            case ROOM_ALREADY_STARTED -> ErrorCode.ROOM_ALREADY_STARTED;
            case NICKNAME_DUPLICATED -> ErrorCode.NICKNAME_DUPLICATED;
            case ALREADY_JOINED -> ErrorCode.ALREADY_JOINED;
            case SUCCESS -> throw new IllegalArgumentException(
                "Successful join has no error code"
            );
        };
    }
}
