package com.plaiground.domain.game.ninja.dto;

import jakarta.validation.constraints.NotBlank;

public record TargetRequest(
    @NotBlank String targetToken
) {
}
