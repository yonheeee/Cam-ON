package com.plaiground.domain.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JoinRoomRequest(
    @NotBlank @Pattern(regexp = "[A-Z0-9]{6}") String roomCode
) {
}
