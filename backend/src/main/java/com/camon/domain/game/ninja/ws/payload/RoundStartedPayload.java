package com.camon.domain.game.ninja.ws.payload;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// 이번 라운드 요구 스킬은 안 실음 — 라운드 콘텐츠는 REST GET(/ninja/rounds/{round}/skill)으로 조회하는 정책.
// alivePlayers/hp 스냅샷을 함께 실어, 폴링 없이 이벤트만 구독하는 클라이언트가 판 시작(전원 부활/
// HP 리셋)을 이 이벤트 하나로 반영할 수 있게 한다.
public record RoundStartedPayload(
    int round,
    int exchange,
    Instant deadlineAt,
    List<String> alivePlayers,
    Map<String, Integer> hp
) {
}
