package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageReceivedPayload(
    int round,
    int turn,
    UUID participantId,
    String nickname,
    String text,
    Instant submittedAt
) {
}
