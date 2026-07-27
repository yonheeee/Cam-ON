package com.camon.domain.game.charades.service;

import com.camon.domain.room.event.ParticipantForcedLeftEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CharadesParticipantEventListener {

    private final CharadesGameService charadesGameService;

    public CharadesParticipantEventListener(
        CharadesGameService charadesGameService
    ) {
        this.charadesGameService = charadesGameService;
    }

    @EventListener
    public void onParticipantForcedLeft(ParticipantForcedLeftEvent event) {
        charadesGameService.handlePresenterForcedLeave(
            event.roomId(),
            event.participantId(),
            event.reason()
        );
    }
}
