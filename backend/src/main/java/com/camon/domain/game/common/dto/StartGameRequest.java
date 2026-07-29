package com.camon.domain.game.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StartGameRequest(
    @NotNull @Positive Long gameId,
    @Min(1) Integer totalRounds
) {
}
