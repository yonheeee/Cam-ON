package com.camon.domain.analytics.domain;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public record AnalyticsDomainEvent(
    UUID eventId,
    AnalyticsEventName eventName,
    UUID roomId,
    UUID participantId,
    String gameType,
    Integer sessionSeq,
    Integer roundNumber,
    Instant occurredAt,
    String appVersion,
    String experimentVersion,
    UUID analyticsUserId,
    Map<String, Object> properties
) {
    public AnalyticsDomainEvent {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static AnalyticsDomainEvent server(
        AnalyticsEventName eventName,
        UUID roomId,
        UUID participantId,
        Instant occurredAt,
        Map<String, Object> properties
    ) {
        return new AnalyticsDomainEvent(
            UUID.randomUUID(),
            eventName,
            roomId,
            participantId,
            null,
            null,
            null,
            occurredAt,
            null,
            null,
            null,
            properties
        );
    }

    public static AnalyticsDomainEvent serverIdempotent(
        AnalyticsEventName eventName,
        UUID roomId,
        UUID participantId,
        String discriminator,
        Instant occurredAt,
        Map<String, Object> properties
    ) {
        UUID eventId = UUID.nameUUIDFromBytes(
            (roomId + ":" + eventName + ":" + discriminator)
                .getBytes(StandardCharsets.UTF_8)
        );
        return new AnalyticsDomainEvent(
            eventId,
            eventName,
            roomId,
            participantId,
            null,
            null,
            null,
            occurredAt,
            null,
            null,
            null,
            properties
        );
    }
}
