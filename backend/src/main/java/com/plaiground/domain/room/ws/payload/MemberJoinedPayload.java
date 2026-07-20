package com.plaiground.domain.room.ws.payload;

import java.util.UUID;

public record MemberJoinedPayload(
    UUID participantId,
    String nickname
) {
}
