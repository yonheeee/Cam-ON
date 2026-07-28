package com.camon.domain.game.ninja.dto;

public record TargetResponse(
    int round,
    int exchange,
    String attackerToken,
    String targetToken,
    Long skillId,
    int damage,
    int targetHpAfter,
    boolean targetEliminated,
    // 이 교환으로 판이 끝났는가(최후 1인).
    boolean boutEnded,
    // 이 공격이 게임 전체를 끝냈는가(마지막 판 종료).
    boolean gameEnded
) {
}
