package com.camon.domain.game.fetch.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public record FetchSubmissionRequest(
    @Min(1)
    int round,
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    Double confidence,
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    Double targetScore
) {
}
