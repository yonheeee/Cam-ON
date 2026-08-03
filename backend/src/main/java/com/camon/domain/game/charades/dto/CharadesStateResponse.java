package com.camon.domain.game.charades.dto;

import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import java.time.Instant;
import java.util.UUID;

public record CharadesStateResponse(
    int round,
    int totalRounds,
    int turn,
    int totalTurnsInRound,
    UUID presenterId,
    Instant expiresAt,
    CharadesTurnStatus status
) {
}
