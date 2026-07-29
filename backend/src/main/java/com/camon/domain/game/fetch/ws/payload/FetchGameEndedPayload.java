package com.camon.domain.game.fetch.ws.payload;

import java.util.List;

public record FetchGameEndedPayload(
    List<FetchScoreEntry> scores
) {
}
