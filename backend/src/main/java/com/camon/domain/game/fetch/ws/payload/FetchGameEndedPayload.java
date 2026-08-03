package com.camon.domain.game.fetch.ws.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FetchGameEndedPayload(
    List<FetchScoreEntry> scores,
    Map<UUID, Long> courseTotals
) {
}
