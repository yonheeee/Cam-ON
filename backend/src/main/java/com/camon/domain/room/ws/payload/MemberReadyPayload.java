package com.camon.domain.room.ws.payload;

import java.util.UUID;

public record MemberReadyPayload(
    UUID participantId,
    boolean ready,
    boolean allReady
) {
}
