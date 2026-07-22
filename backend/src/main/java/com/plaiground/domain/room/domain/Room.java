package com.plaiground.domain.room.domain;

import java.time.Instant;
import java.util.UUID;

public record Room(
    UUID roomId,
    String roomCode,
    String title,
    UUID hostParticipantId,
    int maxPlayers,
    RoomStatus status,
    // 코스에서 진행 중인 세션 위치(1부터) — room:{code}.current_session_seq. 게임 도메인이 이 값과
    // roomCode로 room:{code}:session:{seq}:... 키를 조립한다(예: domain/game/ninja).
    int currentSessionSeq,
    Instant createdAt
) {
}
