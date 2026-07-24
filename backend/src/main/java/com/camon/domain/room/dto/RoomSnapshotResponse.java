package com.camon.domain.room.dto;

import com.camon.domain.room.domain.RoomStatus;
import java.util.List;
import java.util.UUID;

public record RoomSnapshotResponse(
    UUID roomId,
    String roomCode,
    int maxPlayers,
    RoomStatus status,
    UUID hostParticipantId,
    List<ParticipantResponse> participants
) {
}
