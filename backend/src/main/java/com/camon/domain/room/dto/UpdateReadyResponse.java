package com.camon.domain.room.dto;

import java.util.UUID;

public record UpdateReadyResponse(
    UUID participantId,
    boolean ready,
    boolean allReady
) {
}
