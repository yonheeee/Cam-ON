package com.camon.domain.game.charades.ws.payload;

import java.util.UUID;

public record CharadesScoreEntry(
    UUID participantId,
    long roundScore,
    long totalScore,
    int rank
) {
}
