package com.camon.domain.game.charades.dto;

public record CharadesGuessResponse(
    int round,
    boolean correct
) {
}
