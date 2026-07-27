package com.camon.domain.room.event;

import java.util.UUID;

public record ParticipantForcedLeftEvent(
    UUID roomId,
    UUID participantId,
    String reason
) {
}
