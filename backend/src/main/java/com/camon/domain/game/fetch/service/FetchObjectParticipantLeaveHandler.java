package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.service.GameParticipantLeaveHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

// FetchObjectSessionStarter와 같은 관례 — 서비스에 위임만 하는 얇은 어댑터.
@Component
public class FetchObjectParticipantLeaveHandler implements GameParticipantLeaveHandler {

    private final FetchObjectGameService fetchObjectGameService;

    public FetchObjectParticipantLeaveHandler(
        FetchObjectGameService fetchObjectGameService
    ) {
        this.fetchObjectGameService = fetchObjectGameService;
    }

    @Override
    public void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        String reason,
        int connectedCount
    ) {
        fetchObjectGameService.handleParticipantLeft(
            roomId,
            participantId,
            connectedCount
        );
    }
}
