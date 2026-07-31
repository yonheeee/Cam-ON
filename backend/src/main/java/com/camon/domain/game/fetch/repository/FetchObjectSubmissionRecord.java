package com.camon.domain.game.fetch.repository;

import java.time.Instant;
import java.util.UUID;

public record FetchObjectSubmissionRecord(
    UUID participantId,
    int rank,
    Instant submittedAt
) {
}
