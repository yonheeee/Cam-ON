package com.camon.domain.room.ws.payload;

import java.util.UUID;

public record WebRtcSessionDescriptionPayload(
    UUID fromParticipantId,
    String sdp
) {
}
