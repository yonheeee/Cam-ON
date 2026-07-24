package com.camon.domain.room.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateRoomRequest(
    @Min(2) @Max(4) int maxPlayers
) {
}
