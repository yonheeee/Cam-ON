package com.camon.domain.game.fetch.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.camon.domain.game.fetch.ws.payload.FetchRoundStartedPayload;
import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FetchObjectEventPublisherTest {

    @Test
    void publishesFetchEventToRoomTopic() {
        StompBroadcaster broadcaster = mock(StompBroadcaster.class);
        FetchObjectEventPublisher publisher =
            new FetchObjectEventPublisher(broadcaster);
        UUID roomId = UUID.randomUUID();
        FetchRoundStartedPayload payload =
            new FetchRoundStartedPayload(1, 4, "휴대폰", 1234L);

        publisher.publish(roomId, "round:start", payload);

        ArgumentCaptor<Object> eventCaptor =
            ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).send(
            eq("/topic/rooms/" + roomId),
            eventCaptor.capture()
        );
        StompEvent<?> event = (StompEvent<?>) eventCaptor.getValue();
        assertThat(event.event()).isEqualTo("round:start");
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.data()).isEqualTo(payload);
    }
}
