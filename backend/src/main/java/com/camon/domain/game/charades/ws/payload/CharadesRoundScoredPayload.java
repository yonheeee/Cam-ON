package com.camon.domain.game.charades.ws.payload;

import java.util.List;

public record CharadesRoundScoredPayload(
    int round,
    List<CharadesScoreEntry> scores
) {
}
