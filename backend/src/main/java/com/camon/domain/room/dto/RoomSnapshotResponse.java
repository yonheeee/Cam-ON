package com.camon.domain.room.dto;

import com.camon.domain.room.domain.RoomStatus;
import java.util.List;
import java.util.UUID;

public record RoomSnapshotResponse(
    UUID roomId,
    String roomCode,
    int maxPlayers,
    RoomStatus status,
    UUID hostParticipantId,
    // 발표 시연용 방인가 — 대기방이 "시연 모드" 배지를 띄우는 근거. 방 생성 시 정해지고 안 바뀐다.
    boolean demoMode,
    List<ParticipantResponse> participants
) {
}
