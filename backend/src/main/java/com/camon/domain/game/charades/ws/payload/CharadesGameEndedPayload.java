package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.List;

public record CharadesGameEndedPayload(
    int totalRounds,
    Instant endedAt,
    List<CharadesRankingEntry> ranking
) {
}
