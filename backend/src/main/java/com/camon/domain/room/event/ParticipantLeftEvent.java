package com.camon.domain.room.event;

import java.util.UUID;

public record ParticipantLeftEvent(
    UUID roomId,
    UUID participantId,
    String reason
) {
}
