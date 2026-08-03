package com.camon.domain.game.ninja.service;

import com.camon.domain.game.common.service.GameParticipantLeaveHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

// NinjaSessionStarter와 같은 관례 — 서비스에 위임만 하는 얇은 어댑터.
@Component
public class NinjaParticipantLeaveHandler implements GameParticipantLeaveHandler {

    private final NinjaGameService ninjaGameService;

    public NinjaParticipantLeaveHandler(NinjaGameService ninjaGameService) {
        this.ninjaGameService = ninjaGameService;
    }

    @Override
    public void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        String reason,
        int connectedCount
    ) {
        // 닌자는 reason/connectedCount를 보지 않는다 — 왜 나갔든 생존자가 한 명 줄어드는 건
        // 같고, "이제 진행할 수 없다"의 판단은 방 인원이 아니라 이 판의 생존자 수와 세션
        // 참가자 수(startRound의 MIN_PLAYERS)가 한다.
        ninjaGameService.handleParticipantLeft(roomId, participantId);
    }
}
