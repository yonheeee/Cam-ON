package com.camon.domain.game.fetch.dto;

import java.util.UUID;

public record FetchSubmissionResponse(
    int round,
    UUID participantId,
    int rank,
    long score
) {
}
