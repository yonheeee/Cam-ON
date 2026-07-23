package com.plaiground.domain.room.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinRoomRequest(
    @NotBlank String roomCode
) {
}
