package com.camon.domain.game.ninja.ws.payload;

import java.time.Instant;

// 공격권 선점 전파. targetDeadlineAt = 대상 지정 제한시각(서버 기준) — 공격권을 얻는 순간
// 교환 30초 타이머는 멈추고 이 시각까지의 대상 지정 창이 새로 열린다. 제한시간을 넘기면
// 서버가 생존자 중 랜덤 대상으로 자동 공격한다(공격이 무산되는 일은 없다).
public record AttackWonPayload(
    int round,
    int exchange,
    String attackerToken,
    Long skillId,
    Instant targetDeadlineAt
) {
}
