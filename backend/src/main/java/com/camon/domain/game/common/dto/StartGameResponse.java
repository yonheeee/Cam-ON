package com.camon.domain.game.common.dto;

public record StartGameResponse(
    Long gameId,
    int totalRounds
) {
}
