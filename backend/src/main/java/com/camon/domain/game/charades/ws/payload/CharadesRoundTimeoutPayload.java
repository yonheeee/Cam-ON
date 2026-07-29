package com.camon.domain.game.charades.ws.payload;

public record CharadesRoundTimeoutPayload(
    int round,
    int turn
) {
}
