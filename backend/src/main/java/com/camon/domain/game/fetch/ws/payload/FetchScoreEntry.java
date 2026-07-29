package com.camon.domain.game.fetch.ws.payload;

import java.util.UUID;

public record FetchScoreEntry(
    UUID participantId,
    long score,
    int rank
) {
}
