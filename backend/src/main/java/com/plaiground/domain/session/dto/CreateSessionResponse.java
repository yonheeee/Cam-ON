package com.plaiground.domain.session.dto;

import java.util.UUID;

public record CreateSessionResponse(
    UUID participantId,
    String nickname,
    String accessToken
) {
}
