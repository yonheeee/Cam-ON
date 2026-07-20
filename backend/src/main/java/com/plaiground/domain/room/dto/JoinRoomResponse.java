package com.plaiground.domain.room.dto;

public record JoinRoomResponse(
    RoomSnapshotResponse room,
    String livekitToken
) {
}
