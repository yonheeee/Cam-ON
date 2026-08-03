package com.camon.domain.game.fetch.dto;

import com.camon.domain.game.fetch.ws.payload.FetchScoreEntry;
import java.util.List;

public record FetchObjectStateResponse(
    int round,
    int totalRounds,
    String target,
    long startedAt,
    long deadlineAt,
    String status,
    List<FetchObjectSuccessEntry> successes,
    List<FetchScoreEntry> totals
) {
}
