package com.plaiground.domain.room.repository;

import java.util.UUID;

public record LeaveRoomResult(
    LeaveRoomStatus status,
    UUID previousHostParticipantId,
    UUID newHostParticipantId
) {
}
