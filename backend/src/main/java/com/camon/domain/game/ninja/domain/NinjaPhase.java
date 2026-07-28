package com.camon.domain.game.ninja.domain;

// 라운드 진행의 서버 기준 단계. 프론트는 이 값 + 서버 기준 시각(effectUntil/nextRoundAt)만 보고
// 이펙트 재생/카운트다운/입력 허용 여부를 결정한다(클라 로컬 타이머로 진행을 판단하지 않는다).
public enum NinjaPhase {
    // 손동작 입력을 받는 진행 중 라운드.
    ROUND,
    // 공격 resolve(또는 타임아웃) 직후, 다음 라운드가 열리기 전까지의 대기 구간.
    // 이펙트 재생(effectUntil) → 다음 라운드 직전 카운트다운(nextRoundAt) 순으로 노출된다.
    INTERMISSION,
    // 게임 종료. 최종 순위(ranking)가 확정돼 있다.
    ENDED
}
