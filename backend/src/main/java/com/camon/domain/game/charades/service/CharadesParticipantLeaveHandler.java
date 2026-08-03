package com.camon.domain.game.charades.service;

import com.camon.domain.game.common.service.GameParticipantLeaveHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

// CharadesSessionStarter와 같은 관례 — 서비스에 위임만 하는 얇은 어댑터.
@Component
public class CharadesParticipantLeaveHandler implements GameParticipantLeaveHandler {

    private final CharadesGameService charadesGameService;

    public CharadesParticipantLeaveHandler(CharadesGameService charadesGameService) {
        this.charadesGameService = charadesGameService;
    }

    @Override
    public void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        String reason,
        int connectedCount
    ) {
        charadesGameService.handleParticipantLeft(
            roomId,
            participantId,
            reason,
            connectedCount
        );
    }
}
