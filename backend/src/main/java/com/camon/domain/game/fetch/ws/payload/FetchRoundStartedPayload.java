package com.camon.domain.game.fetch.ws.payload;

// startedAt은 프론트 mock과 같은 epoch milliseconds다.
// [startedAt, startedAt+3초)는 카운트다운, 그 뒤 20초가 실제 제출 구간이다.
public record FetchRoundStartedPayload(
    int round,
    int totalRounds,
    String target,
    long startedAt
) {
}
