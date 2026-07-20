package com.plaiground.domain.room.dto;

public record CreateRoomResponse(
    RoomSnapshotResponse room,
    String inviteUrl,
    String livekitToken
) {
}
