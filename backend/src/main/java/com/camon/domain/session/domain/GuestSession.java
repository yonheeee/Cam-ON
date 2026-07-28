package com.camon.domain.session.domain;

import java.time.Instant;
import java.util.UUID;

public record GuestSession(
    UUID participantId,
    String nickname,
    Instant createdAt
) {
}
