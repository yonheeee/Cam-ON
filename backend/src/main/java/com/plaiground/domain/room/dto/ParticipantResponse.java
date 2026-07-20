package com.plaiground.domain.room.dto;

import com.plaiground.domain.room.domain.ConnectionStatus;
import java.util.UUID;

public record ParticipantResponse(
    UUID participantId,
    String nickname,
    String role,
    boolean ready,
    ConnectionStatus connectionStatus
) {
}
