package com.camon.domain.game.ninja.dto;

// 성공(200) = 이 요청이 공격권을 선점했다는 뜻. 선점 실패(이미 다른 참가자가 먼저 완성)는
// ErrorCode.NINJA_ALREADY_CLAIMED로 응답 — 방 관련 도메인의 ROOM_FULL/ALREADY_JOINED와 같은 패턴.
public record AttackResponse(
    int round,
    int exchange,
    String attackerToken,
    Long skillId
) {
}
