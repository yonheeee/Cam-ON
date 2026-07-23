package com.plaiground.domain.game.ninja.ws.payload;

import com.plaiground.domain.game.ninja.dto.RankingEntry;
import java.util.List;

public record GameEndedPayload(
    List<RankingEntry> ranking
) {
}
