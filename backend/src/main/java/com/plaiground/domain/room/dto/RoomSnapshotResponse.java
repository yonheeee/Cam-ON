package com.plaiground.domain.room.dto;

import com.plaiground.domain.room.domain.RoomStatus;
import java.util.List;
import java.util.UUID;

public record RoomSnapshotResponse(
    UUID roomId,
    String roomCode,
    String title,
    int maxPlayers,
    RoomStatus status,
    UUID hostParticipantId,
    List<ParticipantResponse> participants
) {
}
