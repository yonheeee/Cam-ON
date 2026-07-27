package com.camon.domain.game.charades.service;

import com.camon.domain.room.event.ParticipantLeftEvent;
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
    public void onParticipantLeft(ParticipantLeftEvent event) {
        charadesGameService.handleParticipantLeft(
            event.roomId(),
            event.participantId(),
            event.reason()
        );
    }
}
