package com.camon.domain.game.fetch.ws.payload;

public record FetchRoundEndedPayload(
    int round,
    int totalRounds,
    long endedAt
) {
}
