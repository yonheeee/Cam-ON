package com.plaiground.domain.game.ninja.ws.payload;

public record AttackWonPayload(
    int round,
    String attackerToken,
    Long skillId
) {
}
