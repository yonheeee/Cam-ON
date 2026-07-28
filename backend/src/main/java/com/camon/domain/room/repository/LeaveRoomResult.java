package com.camon.domain.room.repository;

import java.util.UUID;

public record LeaveRoomResult(
    LeaveRoomStatus status,
    UUID participantId,
    UUID previousHostParticipantId,
    UUID newHostParticipantId,
    boolean roomDeleted
) {
    public boolean hostChanged() {
        return status == LeaveRoomStatus.SUCCESS
            && previousHostParticipantId != null
            && newHostParticipantId != null
            && !previousHostParticipantId.equals(newHostParticipantId);
    }
}
