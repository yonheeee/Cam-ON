package com.camon.domain.room.dto;

import com.camon.domain.room.domain.ConnectionStatus;
import java.util.UUID;

public record ParticipantResponse(
    UUID participantId,
    String nickname,
    String role,
    boolean ready,
    ConnectionStatus connectionStatus,
    /**
     * 대기방 화면에 있는가. false면 코스 종합 결과에 아직 남아 있는 참가자로, 대기방 타일에
     * 자리는 그대로 두고 "게임 중"으로 표시한다.
     */
    boolean inLobby
) {
}
