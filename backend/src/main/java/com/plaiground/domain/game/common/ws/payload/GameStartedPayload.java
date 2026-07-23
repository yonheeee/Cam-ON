package com.plaiground.domain.game.common.ws.payload;

public record GameStartedPayload(
    Long gameId,
    int sessionSeq,
    int totalRounds
) {
}
