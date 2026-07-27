package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;

public record CharadesGameEndedPayload(
    int totalRounds,
    Instant endedAt
) {
}
