package com.plaiground.domain.game.ninja.dto;

import com.plaiground.domain.game.ninja.domain.Effect;

public record EffectResponse(
    Long id,
    String name,
    String color,
    Integer particleCount,
    Integer life,
    Integer radiusMin,
    Integer radiusMax,
    Integer speedMin,
    Integer speedMax
) {
    public static EffectResponse from(Effect effect) {
        return new EffectResponse(
            effect.getId(),
            effect.getName(),
            effect.getColor(),
            effect.getParticleCount(),
            effect.getLife(),
            effect.getRadiusMin(),
            effect.getRadiusMax(),
            effect.getSpeedMin(),
            effect.getSpeedMax()
        );
    }
}
