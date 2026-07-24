package com.camon.global.apiresponse;

import java.time.Instant;

public record ErrorResponse(
    String code,
    String message,
    Instant timestamp
) {
}
