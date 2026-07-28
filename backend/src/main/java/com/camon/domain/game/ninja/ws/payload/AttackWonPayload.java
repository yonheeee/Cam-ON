package com.camon.domain.game.ninja.ws.payload;

public record AttackWonPayload(
    int round,
    int exchange,
    String attackerToken,
    Long skillId
) {
}
