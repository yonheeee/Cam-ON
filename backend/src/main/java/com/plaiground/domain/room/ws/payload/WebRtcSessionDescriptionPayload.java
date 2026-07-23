package com.plaiground.domain.room.ws.payload;

import java.util.UUID;

public record WebRtcSessionDescriptionPayload(
    UUID fromParticipantId,
    String sdp
) {
}
