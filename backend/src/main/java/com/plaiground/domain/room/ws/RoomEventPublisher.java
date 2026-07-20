package com.plaiground.domain.room.ws;

import com.plaiground.global.ws.StompBroadcaster;
import com.plaiground.global.ws.StompEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RoomEventPublisher {
    private final StompBroadcaster broadcaster;

    public RoomEventPublisher(StompBroadcaster broadcaster) {
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
