package com.plaiground.domain.game.ninja.ws.payload;

public record AttackResolvedPayload(
    int round,
    String attackerToken,
    String targetToken,
    Long skillId,
    int damage,
    int targetHpAfter,
    boolean targetEliminated
) {
}
