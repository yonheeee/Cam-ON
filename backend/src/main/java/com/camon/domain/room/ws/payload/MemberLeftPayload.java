package com.camon.domain.room.ws.payload;

import java.util.UUID;

public record MemberLeftPayload(
    UUID participantId,
    String reason,
    UUID newHostParticipantId
) {
}
