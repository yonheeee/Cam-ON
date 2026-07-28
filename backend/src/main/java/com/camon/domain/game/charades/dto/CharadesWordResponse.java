package com.camon.domain.game.charades.dto;

import java.time.Instant;

public record CharadesWordResponse(
    int round,
    int turn,
    String word,
    Instant expiresAt
) {
}
