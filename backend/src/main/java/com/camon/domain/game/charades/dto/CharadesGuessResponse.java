package com.camon.domain.game.charades.dto;

public record CharadesGuessResponse(
    int round,
    int turn,
    boolean correct
) {
}
