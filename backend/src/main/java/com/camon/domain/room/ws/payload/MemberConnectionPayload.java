package com.camon.domain.room.ws.payload;

import com.camon.domain.room.domain.ConnectionStatus;
import java.util.UUID;

/**
 * 참가자의 연결 상태가 바뀌었을 때 방 전체에 알리는 payload.
 * DISCONNECTED는 "나갔다"가 아니라 "재접속 유예 중"이라는 뜻이다 — 유예가 끝나 실제로
 * 퇴장하면 그때 별도로 member:left가 나간다.
 */
public record MemberConnectionPayload(
    UUID participantId,
    ConnectionStatus connectionStatus
) {
}
