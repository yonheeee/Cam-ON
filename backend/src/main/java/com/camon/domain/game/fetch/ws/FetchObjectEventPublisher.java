package com.camon.domain.game.fetch.ws;

import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FetchObjectEventPublisher {

    private final StompBroadcaster broadcaster;

    public FetchObjectEventPublisher(StompBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void publish(UUID roomId, String eventName, Object payload) {
        broadcaster.send(
            "/topic/rooms/" + roomId,
            new StompEvent<>(
                UUID.randomUUID(),
                eventName,
                roomId,
                Instant.now(),
                payload
            )
        );
    }
}
