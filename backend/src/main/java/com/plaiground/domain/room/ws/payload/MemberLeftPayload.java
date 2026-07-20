package com.plaiground.domain.room.ws.payload;

import java.util.UUID;

public record MemberLeftPayload(
    UUID participantId,
    String reason,
    UUID newHostParticipantId
) {
}
