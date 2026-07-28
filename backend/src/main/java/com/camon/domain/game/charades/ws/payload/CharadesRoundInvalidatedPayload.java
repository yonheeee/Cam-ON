package com.camon.domain.game.charades.ws.payload;

import java.util.UUID;

public record CharadesRoundInvalidatedPayload(
    int round,
    int turn,
    UUID presenterId,
    String reason
) {
}
