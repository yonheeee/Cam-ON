package com.camon.domain.room.dto;

import com.camon.domain.room.domain.ConnectionStatus;
import java.util.UUID;

public record ParticipantResponse(
    UUID participantId,
    String nickname,
    String role,
    boolean ready,
    ConnectionStatus connectionStatus
) {
}
