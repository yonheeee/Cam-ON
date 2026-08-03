package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CharadesGameEndedPayload(
    int totalRounds,
    Instant endedAt,
    List<CharadesRankingEntry> ranking,
    Map<UUID, Long> courseTotals
) {
}
