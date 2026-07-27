package com.camon.domain.game.ninja.dto;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 재접속 시 스냅샷 동기화용. WS 이벤트는 끊긴 동안 놓치므로, 다시 붙었을 때 이걸로 한 번 맞춰준다.
// ranking은 게임이 끝나기 전까지 빈 리스트 — STOMP를 안 붙인 클라이언트도 폴링만으로 최종 순위를
// 알 수 있게 하는 용도(ninja:game-ended WS 이벤트와 같은 정보를 REST로도 노출).
//
// phase/effectUntil/nextRoundAt/lastAttack은 "공격 resolve → 이펙트 → 카운트다운 → 다음 라운드"
// 인터미션을 서버 주도로 굴리기 위한 필드다. 진행/전환을 클라 로컬 타이머가 아니라 이 서버 기준
// 시각으로만 판단하게 해서 전원 동일 타이밍(선입력 방지)을 보장한다. 인터미션이 아닐 땐
// effectUntil/nextRoundAt/lastAttack은 null.
public record NinjaStateResponse(
    int round,
    int totalRounds,
    List<String> alivePlayers,
    Map<String, Integer> hp,
    String currentAttackerToken,
    List<RankingEntry> ranking,
    NinjaPhase phase,
    Instant effectUntil,
    Instant nextRoundAt,
    LastAttackResponse lastAttack
) {
}
