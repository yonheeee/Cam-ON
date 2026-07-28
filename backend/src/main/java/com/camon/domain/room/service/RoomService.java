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
import com.camon.domain.room.repository.KickParticipantResult;
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
                // 방장은 준비 토글 대신 "게임 시작" 버튼을 쓰므로 생성 시점부터 준비 완료로 둔다
                // (UI에도 방장이 "준비됨"으로 표시되고, 게임 시작의 전원-ready 검사도 특별취급 불필요).
                true,
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
        // 위임 사실을 member:left의 부가 필드로만 흘리면, 그 이벤트 하나를 놓친 클라이언트는
        // (재접속 중이었거나 스냅샷 로딩 전이었으면) 떠난 사람을 계속 방장으로 들고 있게 된다.
        // 방장 교체는 그 자체로 독립된 사건이라 별도 이벤트로도 전파한다.
        if (result.hostChanged()) {
            roomEventPublisher.publishHostChanged(
                roomId,
                result.previousHostParticipantId(),
                result.newHostParticipantId()
            );
        }
        applicationEventPublisher.publishEvent(
            new ParticipantLeftEvent(
                roomId,
                participantId,
                "LEFT"
            )
        );
    }

    public void kick(UUID roomId, UUID requesterId, UUID targetId) {
        KickParticipantResult result = participantRepository.kick(
            roomId,
            requesterId,
            targetId
        );
        if (result != KickParticipantResult.SUCCESS) {
            throw new BusinessException(toErrorCode(result));
        }

        // 강퇴도 퇴장의 한 형태 — 이유만 다르게 실어 같은 채널(member:left)로 전파한다.
        // 강퇴당한 본인 클라이언트도 이 브로드캐스트에서 자기 id + KICKED를 보고 방을 떠난다.
        // 방장은 대상이 될 수 없으므로(스크립트가 SELF_KICK/NOT_HOST로 거른다) 위임은 없다.
        roomEventPublisher.publishMemberLeft(roomId, targetId, null, "KICKED");
        applicationEventPublisher.publishEvent(
            new ParticipantLeftEvent(roomId, targetId, "KICKED")
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
            case BANNED -> ErrorCode.ROOM_BANNED;
            case SUCCESS -> throw new IllegalArgumentException(
                "Successful join has no error code"
            );
        };
    }

    private ErrorCode toErrorCode(KickParticipantResult result) {
        return switch (result) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case ROOM_ALREADY_STARTED -> ErrorCode.ROOM_ALREADY_STARTED;
            case NOT_HOST -> ErrorCode.ROOM_NOT_HOST;
            case SELF_KICK -> ErrorCode.ROOM_KICK_SELF;
            case PARTICIPANT_NOT_FOUND -> ErrorCode.ROOM_PARTICIPANT_NOT_FOUND;
            case SUCCESS -> throw new IllegalArgumentException(
                "Successful kick has no error code"
            );
        };
    }
}
