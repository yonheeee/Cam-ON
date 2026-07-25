package com.camon.domain.game.ninja.dto;

public record TargetResponse(
    int round,
    String attackerToken,
    String targetToken,
    Long skillId,
    int damage,
    int targetHpAfter,
    boolean targetEliminated,
    boolean gameEnded
) {
}
