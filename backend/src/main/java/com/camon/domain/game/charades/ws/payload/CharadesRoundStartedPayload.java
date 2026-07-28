package com.camon.domain.game.charades.ws.payload;

public record CharadesRoundStartedPayload(
    int round,
    int totalRounds,
    int totalTurnsInRound
) {
}
