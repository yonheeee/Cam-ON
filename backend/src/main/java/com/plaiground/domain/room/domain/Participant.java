package com.plaiground.domain.room.domain;

import java.time.Instant;
import java.util.UUID;

public record Participant(
    UUID participantId,
    String nickname,
    boolean ready,
    ConnectionStatus connectionStatus,
    Instant joinedAt
) {
}
