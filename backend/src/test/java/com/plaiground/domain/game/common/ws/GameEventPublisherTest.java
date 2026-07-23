package com.plaiground.domain.game.common.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.plaiground.domain.game.common.ws.payload.GameStartedPayload;
import com.plaiground.global.ws.StompBroadcaster;
import com.plaiground.global.ws.StompEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GameEventPublisherTest {

    @Test
    void publishesGameStartedToRoomTopic() {
        StompBroadcaster broadcaster = mock(StompBroadcaster.class);
        GameEventPublisher publisher = new GameEventPublisher(broadcaster);
        UUID roomId = UUID.randomUUID();

        publisher.publishStarted(roomId, 3L, 2, 5);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).send(
            eq("/topic/rooms/" + roomId),
            eventCaptor.capture()
        );
        StompEvent<?> event = (StompEvent<?>) eventCaptor.getValue();
        assertThat(event.event()).isEqualTo("game:started");
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.data()).isEqualTo(
            new GameStartedPayload(3L, 2, 5)
        );
    }
}
