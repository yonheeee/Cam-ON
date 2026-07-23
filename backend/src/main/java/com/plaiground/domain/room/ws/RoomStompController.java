package com.plaiground.domain.room.ws;

import com.plaiground.domain.room.service.RoomConnectionService;
import com.plaiground.domain.room.service.RoomSignalingService;
import com.plaiground.domain.room.ws.payload.WebRtcIceCandidateRequest;
import com.plaiground.domain.room.ws.payload.WebRtcSessionDescriptionRequest;
import com.plaiground.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
public class RoomStompController {

    private final RoomConnectionService connectionService;
    private final RoomSignalingService signalingService;

    public RoomStompController(
        RoomConnectionService connectionService,
        RoomSignalingService signalingService
    ) {
        this.connectionService = connectionService;
        this.signalingService = signalingService;
    }

    @MessageMapping("/rooms/{roomId}/heartbeat")
    public void heartbeat(
        @DestinationVariable UUID roomId,
        Principal principal
    ) {
        connectionService.heartbeat(roomId, participantId(principal));
    }

    @MessageMapping("/rooms/{roomId}/webrtc/offer")
    public void offer(
        @DestinationVariable UUID roomId,
        Principal principal,
        @Valid WebRtcSessionDescriptionRequest request
    ) {
        signalingService.relayOffer(
            roomId,
            participantId(principal),
            request
        );
    }

    @MessageMapping("/rooms/{roomId}/webrtc/answer")
    public void answer(
        @DestinationVariable UUID roomId,
        Principal principal,
        @Valid WebRtcSessionDescriptionRequest request
    ) {
        signalingService.relayAnswer(
            roomId,
            participantId(principal),
            request
        );
    }

    @MessageMapping("/rooms/{roomId}/webrtc/ice-candidate")
    public void iceCandidate(
        @DestinationVariable UUID roomId,
        Principal principal,
        @Valid WebRtcIceCandidateRequest request
    ) {
        signalingService.relayIceCandidate(
            roomId,
            participantId(principal),
            request
        );
    }

    private static UUID participantId(Principal principal) {
        if (principal instanceof org.springframework.security.core.Authentication auth
            && auth.getPrincipal() instanceof GuestPrincipal guestPrincipal) {
            return guestPrincipal.participantId();
        }
        throw new org.springframework.security.authentication.BadCredentialsException(
            "STOMP session is not authenticated"
        );
    }
}
