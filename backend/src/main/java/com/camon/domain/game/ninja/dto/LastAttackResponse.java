package com.camon.domain.game.ninja.dto;

// INTERMISSION 구간에서 "방금 무슨 공격이 들어갔는지"를 스냅샷으로 노출한다 — 재접속/늦은 폴링
// 클라이언트도 이 정보 + 라운드 스킬(effect)만으로 이펙트를 그대로 재현할 수 있게 하기 위함.
// 타임아웃으로 넘어간 인터미션(공격이 없었던 경우)에는 null이다.
public record LastAttackResponse(
    String attackerToken,
    String targetToken,
    Long skillId,
    int damage,
    int targetHpAfter,
    boolean targetEliminated
) {
}
