package com.camon.domain.room.service;

import com.camon.domain.media.service.LiveKitTokenService;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.dto.CreateRoomRequest;
import com.camon.domain.room.dto.CreateRoomResponse;
import com.camon.domain.room.dto.JoinRoomRequest;
import com.camon.domain.room.dto.JoinRoomResponse;
import com.camon.domain.room.dto.ParticipantResponse;
import com.camon.domain.room.dto.RoomSnapshotResponse;
import com.camon.domain.room.dto.UpdateReadyRequest;
import com.camon.domain.room.dto.UpdateReadyResponse;
import com.camon.domain.room.event.ParticipantLeftEvent;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.domain.room.repository.JoinParticipantResult;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ReadyUpdateResult;
import com.camon.domain.room.repository.ReadyUpdateStatus;
import com.camon.domain.room.ws.RoomEventPublisher;
import com.camon.domain.session.domain.GuestSession;
import com.camon.domain.session.repository.SessionRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RoomService {

    private static final int MAX_CODE_GENERATION_ATTEMPTS = 10;

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final SessionRepository sessionRepository;
    private final RoomCodeGenerator roomCodeGenerator;
    private final RoomInviteLinkGenerator inviteLinkGenerator;
    private final RoomEventPublisher roomEventPublisher;
    private final LiveKitTokenService liveKitTokenService;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    public RoomService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        SessionRepository sessionRepository,
        RoomCodeGenerator roomCodeGenerator,
        RoomInviteLinkGenerator inviteLinkGenerator,
        RoomEventPublisher roomEventPublisher,
        LiveKitTokenService liveKitTokenService,
        Clock jwtClock,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.sessionRepository = sessionRepository;
        this.roomCodeGenerator = roomCodeGenerator;
        this.inviteLinkGenerator = inviteLinkGenerator;
        this.roomEventPublisher = roomEventPublisher;
        this.liveKitTokenService = liveKitTokenService;
        this.clock = jwtClock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public CreateRoomResponse createRoom(
        UUID participantId,
        CreateRoomRequest request
    ) {
        if (participantRepository.findCurrentRoomId(participantId).isPresent()) {
            throw new BusinessException(ErrorCode.ALREADY_JOINED);
        }
        GuestSession guestSession = sessionRepository
            .findByParticipantId(participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        Instant createdAt = clock.instant();

        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            Room room = new Room(
                UUID.randomUUID(),
                roomCodeGenerator.generate(),
                participantId,
                request.maxPlayers(),
                RoomStatus.WAITING,
                1,
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

        roomEventPublisher.publishMemberJoined(
            room.roomId(),
            participant.participantId(),
            participant.nickname()
        );

        Room currentRoom = roomRepository.findById(room.roomId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        return new JoinRoomResponse(
            toRoomSnapshot(currentRoom),
            liveKitTokenService.createRoomJoinToken(
                room.roomId(),
                participant.participantId(),
                participant.nickname()
            )
        );
    }

    public RoomSnapshotResponse getRoom(UUID roomId, UUID participantId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        participantRepository.findById(roomId, participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_ACCESS_DENIED));
        return toRoomSnapshot(room);
    }

    public void leaveRoom(UUID roomId, UUID participantId) {
        LeaveRoomResult result = participantRepository.leave(roomId, participantId);
        if (result.status() == LeaveRoomStatus.ROOM_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (result.status() == LeaveRoomStatus.PARTICIPANT_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_ACCESS_DENIED);
        }

        UUID newHostParticipantId = result.hostChanged()
            ? result.newHostParticipantId()
            : null;
        roomEventPublisher.publishMemberLeft(
            roomId,
            participantId,
            newHostParticipantId
        );
        applicationEventPublisher.publishEvent(
            new ParticipantLeftEvent(
                roomId,
                participantId,
                "LEFT"
            )
        );
    }

    public UpdateReadyResponse updateReady(
        UUID roomId,
        UUID participantId,
        UpdateReadyRequest request
    ) {
        ReadyUpdateResult result = participantRepository.updateReady(
            roomId,
            participantId,
            request.ready()
        );
        if (result.status() == ReadyUpdateStatus.ROOM_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (result.status() == ReadyUpdateStatus.PARTICIPANT_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_ACCESS_DENIED);
        }
        if (result.status() == ReadyUpdateStatus.ROOM_ALREADY_STARTED) {
            throw new BusinessException(ErrorCode.ROOM_ALREADY_STARTED);
        }

        roomEventPublisher.publishMemberReadyUpdated(
            roomId,
            participantId,
            result.ready(),
            result.allReady()
        );
        return new UpdateReadyResponse(
            participantId,
            result.ready(),
            result.allReady()
        );
    }

    private CreateRoomResponse toCreateRoomResponse(
        Room room,
        Participant host
    ) {
        return new CreateRoomResponse(
            toRoomSnapshot(room, List.of(host)),
            inviteLinkGenerator.generate(room.roomCode()),
            liveKitTokenService.createRoomJoinToken(
                room.roomId(),
                host.participantId(),
                host.nickname()
            )
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
