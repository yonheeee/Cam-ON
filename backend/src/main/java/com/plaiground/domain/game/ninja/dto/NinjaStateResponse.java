package com.plaiground.domain.game.ninja.dto;

import java.util.List;
import java.util.Map;

// 재접속 시 스냅샷 동기화용. WS 이벤트는 끊긴 동안 놓치므로, 다시 붙었을 때 이걸로 한 번 맞춰준다.
// ranking은 게임이 끝나기 전까지 빈 리스트 — STOMP를 안 붙인 클라이언트도 폴링만으로 최종 순위를
// 알 수 있게 하는 용도(ninja:game-ended WS 이벤트와 같은 정보를 REST로도 노출).
public record NinjaStateResponse(
    int round,
    int totalRounds,
    List<String> alivePlayers,
    Map<String, Integer> hp,
    String currentAttackerToken,
    List<RankingEntry> ranking
) {
}
