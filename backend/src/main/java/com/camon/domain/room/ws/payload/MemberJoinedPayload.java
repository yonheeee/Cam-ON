package com.camon.domain.room.ws.payload;

import java.util.UUID;

public record MemberJoinedPayload(
    UUID participantId,
    String nickname
) {
}
