package com.camon.domain.game.fetch.dto;

import com.camon.domain.game.fetch.ws.payload.FetchScoreEntry;
import java.util.List;
import java.util.UUID;

public record FetchObjectStateResponse(
    int round,
    int totalRounds,
    String target,
    long startedAt,
    long deadlineAt,
    String status,
    List<FetchObjectSuccessEntry> successes,
    List<FetchScoreEntry> totals,
    /** 현재 라운드 스킵 투표자 (접속 참가자와의 교집합) — 새로고침 시 카운터 복구용 */
    List<UUID> skipVotes
) {
}
