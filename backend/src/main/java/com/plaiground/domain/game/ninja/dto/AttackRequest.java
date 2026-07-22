package com.plaiground.domain.game.ninja.dto;

import jakarta.validation.constraints.NotNull;

public record AttackRequest(
    @NotNull Long skillId
) {
}
