package com.camon.domain.game.fetch.ws.payload;

import java.util.List;

public record FetchRoundEndedPayload(
    int round,
    int totalRounds,
    long endedAt,
    List<FetchScoreEntry> scores
) {
}
