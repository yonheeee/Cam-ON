package com.plaiground.domain.room.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record JoinRoomRequest(
    @NotNull UUID roomId
) {
}
