package com.plaiground.domain.room.domain;

import java.time.Instant;
import java.util.UUID;

public record Room(
    UUID roomId,
    String roomCode,
    String title,
    UUID hostParticipantId,
    int maxPlayers,
    RoomStatus status,
    Instant createdAt
) {
}
