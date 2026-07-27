package com.camon.domain.game.ninja.ws.payload;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import java.time.Instant;

// 공격 resolve 직후 전파. phase/effectUntil/nextRoundAt를 함께 실어, WS를 구독하는 클라이언트가
// 폴링을 기다리지 않고 즉시 인터미션(이펙트→카운트다운)으로 전환할 수 있게 한다. 전환의 실제
// 기준은 이 서버 기준 시각들이라, WS로 받든 폴링(GET .../state)으로 받든 결과 타이밍은 동일하다.
// ending=true면 이 공격이 게임을 끝낸 결정타 — 이펙트(effectUntil)만 재생하고 카운트다운 없이
// 최종 순위로 넘어간다(그래서 nextRoundAt은 null).
public record AttackResolvedPayload(
    int round,
    String attackerToken,
    String targetToken,
    Long skillId,
    int damage,
    int targetHpAfter,
    boolean targetEliminated,
    NinjaPhase phase,
    Instant effectUntil,
    Instant nextRoundAt,
    boolean ending
) {
}
