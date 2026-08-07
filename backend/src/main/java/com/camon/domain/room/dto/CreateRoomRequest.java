package com.camon.domain.room.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateRoomRequest(
    @Min(2) @Max(4) int maxPlayers,
    // 발표 시연용 방으로 열지. 프론트의 숨은 트리거(로고 5연타)만 이 값을 true로 보낸다 —
    // 안 보내면 null이라 평범한 방이 된다.
    Boolean demoMode
) {

    public CreateRoomRequest(int maxPlayers) {
        this(maxPlayers, false);
    }

    public boolean isDemoMode() {
        return Boolean.TRUE.equals(demoMode);
    }
}
