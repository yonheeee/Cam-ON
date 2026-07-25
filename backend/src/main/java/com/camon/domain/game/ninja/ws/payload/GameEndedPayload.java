package com.camon.domain.game.ninja.ws.payload;

import com.camon.domain.game.ninja.dto.RankingEntry;
import java.util.List;

public record GameEndedPayload(
    List<RankingEntry> ranking
) {
}
