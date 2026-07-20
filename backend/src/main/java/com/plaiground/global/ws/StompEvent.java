package com.plaiground.global.ws;

import java.time.Instant;
import java.util.UUID;

public record StompEvent<T>(
    UUID eventId,
    String event,
    UUID roomId,
    Instant occurredAt,
    T data
) {
}
