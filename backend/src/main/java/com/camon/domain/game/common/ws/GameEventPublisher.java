package com.camon.domain.game.common.ws;

import com.camon.domain.game.common.ws.payload.GameStartedPayload;
import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class GameEventPublisher {

    private final StompBroadcaster broadcaster;

    public GameEventPublisher(StompBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void publishStarted(
        UUID roomId,
        Long gameId,
        int sessionSeq,
        int totalRounds
    ) {
        broadcaster.send(
            "/topic/rooms/" + roomId,
            new StompEvent<>(
                UUID.randomUUID(),
                "game:started",
                roomId,
                Instant.now(),
                new GameStartedPayload(gameId, sessionSeq, totalRounds)
            )
        );
    }
}
