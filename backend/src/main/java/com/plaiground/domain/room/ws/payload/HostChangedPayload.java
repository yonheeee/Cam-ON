package com.plaiground.domain.room.ws.payload;

import java.util.UUID;

public record HostChangedPayload(
    UUID previousHostParticipantId,
    UUID newHostParticipantId
) {
}
