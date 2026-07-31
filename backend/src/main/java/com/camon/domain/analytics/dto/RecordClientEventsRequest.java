package com.camon.domain.analytics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record RecordClientEventsRequest(
    UUID analyticsUserId,
    @Size(max = 50) String appVersion,
    @Size(max = 100) String experimentVersion,
    @NotEmpty @Size(max = 20) List<@Valid ClientAnalyticsEventRequest> events
) {
}
