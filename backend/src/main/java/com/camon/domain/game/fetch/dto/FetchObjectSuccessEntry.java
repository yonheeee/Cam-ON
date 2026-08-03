package com.camon.domain.game.fetch.dto;

import java.util.UUID;

public record FetchObjectSuccessEntry(
    UUID participantId,
    int rank,
    long score,
    long submittedAt
) {
}
