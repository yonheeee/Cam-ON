package com.camon.domain.game.charades.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CharadesGuessRequest(
    @NotBlank
    @Size(max = 200)
    String text
) {
}
