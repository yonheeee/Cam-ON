package com.camon.domain.room.dto;

public record JoinRoomResponse(
    RoomSnapshotResponse room,
    String livekitToken
) {
}
