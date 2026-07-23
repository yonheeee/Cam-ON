package com.plaiground.domain.room.ws.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WebRtcSessionDescriptionRequest(
    @NotNull UUID targetParticipantId,
    @NotBlank String sdp
) {
}
