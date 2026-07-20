package com.plaiground.global.ws;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class StompBroadcaster {
    private final SimpMessagingTemplate messagingTemplate;

    public StompBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void send(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
    }
}
