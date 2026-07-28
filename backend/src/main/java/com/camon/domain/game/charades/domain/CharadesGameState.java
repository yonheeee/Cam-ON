package com.camon.domain.game.charades.domain;

import java.time.Instant;
import java.util.UUID;

public record CharadesGameState(
    int currentRound,
    int totalRounds,
    int currentTurn,
    int totalTurnsInRound,
    Long topicId,
    UUID presenterId,
    Long missionId,
    Instant expiresAt,
    CharadesTurnStatus status
) {
}
