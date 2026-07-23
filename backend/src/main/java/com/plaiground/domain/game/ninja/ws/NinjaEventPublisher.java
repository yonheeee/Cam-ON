package com.plaiground.domain.game.ninja.ws;

import com.plaiground.global.ws.StompBroadcaster;
import com.plaiground.global.ws.StompEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

// room 도메인의 RoomEventPublisher와 같은 패턴(얇은 StompEvent 래퍼)을 이 도메인 안에 독립적으로 둔다 —
// 다른 도메인의 ws 퍼블리셔를 직접 참조하지 않는다는 패키지 강령을 따름. 목적지는 room과 동일한
// /topic/rooms/{roomId}를 그대로 재사용(프론트가 방 하나당 토픽 하나만 구독하면 되도록).
@Component
public class NinjaEventPublisher {
    private final StompBroadcaster broadcaster;

    public NinjaEventPublisher(StompBroadcaster broadcaster) {
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
