package com.camon.domain.game.fetch.ws.payload;

import java.util.List;

public record FetchRoundEndedPayload(
    int round,
    int totalRounds,
    long endedAt,
    List<FetchScoreEntry> scores,
    /** 스킵 투표 가결로 끝난 라운드 — 프론트가 "시간 초과"와 구분해 배너를 띄운다. */
    boolean skipped
) {
}
