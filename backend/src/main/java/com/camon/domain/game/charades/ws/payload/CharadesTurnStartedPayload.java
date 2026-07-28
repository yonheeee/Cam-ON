package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.UUID;

public record CharadesTurnStartedPayload(
    int round,
    int turn,
    int totalTurnsInRound,
    UUID presenterId,
    Instant expiresAt
) {
}
