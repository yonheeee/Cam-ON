package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.UUID;

public record CharadesAnswerRevealedPayload(
    int round,
    int turn,
    UUID presenterId,
    UUID answererId,
    Instant answeredAt
) {
}
