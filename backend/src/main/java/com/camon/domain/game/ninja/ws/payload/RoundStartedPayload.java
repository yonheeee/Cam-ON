package com.camon.domain.game.ninja.ws.payload;

import java.time.Instant;

// 이번 라운드 요구 스킬은 안 실음 — 라운드 콘텐츠는 REST GET(/ninja/rounds/{round}/skill)으로 조회하는 정책.
public record RoundStartedPayload(
    int round,
    Instant deadlineAt
) {
}
