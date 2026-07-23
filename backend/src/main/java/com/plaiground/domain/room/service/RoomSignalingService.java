package com.plaiground.domain.room.service;

import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.ws.RoomEventPublisher;
import com.plaiground.domain.room.ws.payload.WebRtcIceCandidatePayload;
import com.plaiground.domain.room.ws.payload.WebRtcIceCandidateRequest;
import com.plaiground.domain.room.ws.payload.WebRtcSessionDescriptionPayload;
import com.plaiground.domain.room.ws.payload.WebRtcSessionDescriptionRequest;
import com.plaiground.global.exception.BusinessException;
import com.plaiground.global.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoomSignalingService {

    private final ParticipantRepository participantRepository;
    private final RoomEventPublisher roomEventPublisher;

    public RoomSignalingService(
        ParticipantRepository participantRepository,
        RoomEventPublisher roomEventPublisher
    ) {
        this.participantRepository = participantRepository;
        this.roomEventPublisher = roomEventPublisher;
    }

    public void relayOffer(
        UUID roomId,
        UUID fromParticipantId,
        WebRtcSessionDescriptionRequest request
    ) {
        relaySessionDescription(
            roomId,
            fromParticipantId,
            request,
            "webrtc:offer"
        );
    }

    public void relayAnswer(
        UUID roomId,
        UUID fromParticipantId,
        WebRtcSessionDescriptionRequest request
    ) {
        relaySessionDescription(
            roomId,
            fromParticipantId,
            request,
            "webrtc:answer"
        );
    }

    public void relayIceCandidate(
        UUID roomId,
        UUID fromParticipantId,
        WebRtcIceCandidateRequest request
    ) {
        validateTarget(roomId, request.targetParticipantId());
        roomEventPublisher.publishToParticipant(
            roomId,
            request.targetParticipantId(),
            "webrtc:ice-candidate",
            new WebRtcIceCandidatePayload(
                fromParticipantId,
                request.candidate(),
                request.sdpMid(),
                request.sdpMLineIndex()
            )
        );
    }

    private void relaySessionDescription(
        UUID roomId,
        UUID fromParticipantId,
        WebRtcSessionDescriptionRequest request,
        String eventName
    ) {
        validateTarget(roomId, request.targetParticipantId());
        roomEventPublisher.publishToParticipant(
            roomId,
            request.targetParticipantId(),
            eventName,
            new WebRtcSessionDescriptionPayload(
                fromParticipantId,
                request.sdp()
            )
        );
    }

    private void validateTarget(UUID roomId, UUID targetParticipantId) {
        if (participantRepository.findById(
            roomId,
            targetParticipantId
        ).isEmpty()) {
            throw new BusinessException(ErrorCode.ROOM_ACCESS_DENIED);
        }
    }
}
