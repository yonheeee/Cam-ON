package com.plaiground.domain.room.ws.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WebRtcIceCandidateRequest(
    @NotNull UUID targetParticipantId,
    @NotBlank String candidate,
    String sdpMid,
    Integer sdpMLineIndex
) {
}
