package com.camon.domain.analytics.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ClientAnalyticsEventRequest(
    @NotNull UUID eventId,
    @NotNull ClientAnalyticsEventType eventName,
    @NotNull Instant occurredAt,
    @Size(max = 30) String gameType,
    Integer sessionSeq,
    Integer roundNumber,
    @Size(max = 20) Map<String, Object> properties
) {
}
