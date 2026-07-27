package com.camon.domain.game.charades.ws.payload;

import java.util.UUID;

public record CharadesRankingEntry(
    UUID participantId,
    long totalScore,
    int rank
) {
}
