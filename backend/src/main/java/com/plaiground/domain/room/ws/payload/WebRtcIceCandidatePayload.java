package com.plaiground.domain.room.ws.payload;

import java.util.UUID;

public record WebRtcIceCandidatePayload(
    UUID fromParticipantId,
    String candidate,
    String sdpMid,
    Integer sdpMLineIndex
) {
}
