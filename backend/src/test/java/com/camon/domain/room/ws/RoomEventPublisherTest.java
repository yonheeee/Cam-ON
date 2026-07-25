package com.camon.domain.room.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.camon.domain.room.ws.payload.MemberReadyPayload;
import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoomEventPublisherTest {

    @Test
    void publishesReadyEventToRoomTopic() {
        StompBroadcaster broadcaster = mock(StompBroadcaster.class);
        RoomEventPublisher publisher = new RoomEventPublisher(broadcaster);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        publisher.publishMemberReadyUpdated(
            roomId,
            participantId,
            true,
            false
        );

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster).send(
            org.mockito.ArgumentMatchers.eq("/topic/rooms/" + roomId),
            eventCaptor.capture()
        );
        StompEvent<?> event = (StompEvent<?>) eventCaptor.getValue();
        assertThat(event.event()).isEqualTo("member:ready-updated");
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.data()).isEqualTo(new MemberReadyPayload(
            participantId,
            true,
            false
        ));
    }
}
