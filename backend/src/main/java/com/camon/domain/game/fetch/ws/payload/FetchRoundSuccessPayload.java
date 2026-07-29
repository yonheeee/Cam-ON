package com.camon.domain.game.fetch.ws.payload;

import java.util.UUID;

public record FetchRoundSuccessPayload(
    UUID participantId,
    int rank,
    long score
) {
}
