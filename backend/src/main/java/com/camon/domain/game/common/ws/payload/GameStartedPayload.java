package com.camon.domain.game.common.ws.payload;

public record GameStartedPayload(
    Long gameId,
    int sessionSeq,
    int totalRounds
) {
}
