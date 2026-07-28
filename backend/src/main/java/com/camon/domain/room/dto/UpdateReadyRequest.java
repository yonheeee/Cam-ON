package com.camon.domain.room.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateReadyRequest(
    @NotNull Boolean ready
) {
}
