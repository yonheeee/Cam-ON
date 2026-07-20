package com.plaiground.domain.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
    @NotBlank @Size(max = 20) String nickname
) {
}
