package com.plaiground.domain.game.ninja.dto;

import java.util.List;
import java.util.Map;

// 재접속 시 스냅샷 동기화용. WS 이벤트는 끊긴 동안 놓치므로, 다시 붙었을 때 이걸로 한 번 맞춰준다.
public record NinjaStateResponse(
    int round,
    int totalRounds,
    List<String> alivePlayers,
    Map<String, Integer> hp,
    String currentAttackerToken
) {
}
