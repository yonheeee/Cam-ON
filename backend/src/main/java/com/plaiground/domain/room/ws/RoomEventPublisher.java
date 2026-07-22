package com.plaiground.domain.room.ws;

import com.plaiground.global.ws.StompBroadcaster;
import com.plaiground.global.ws.StompEvent;
import com.plaiground.domain.room.ws.payload.HostChangedPayload;
import com.plaiground.domain.room.ws.payload.MemberJoinedPayload;
import com.plaiground.domain.room.ws.payload.MemberLeftPayload;
import com.plaiground.domain.room.ws.payload.MemberReadyPayload;
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

    public void publishMemberJoined(
        UUID roomId,
        UUID participantId,
        String nickname
    ) {
        publish(
            roomId,
            "MEMBER_JOINED",
            new MemberJoinedPayload(participantId, nickname)
        );
    }

    public void publishMemberLeft(
        UUID roomId,
        UUID participantId,
        UUID newHostParticipantId
    ) {
        publish(
            roomId,
            "MEMBER_LEFT",
            new MemberLeftPayload(
                participantId,
                "LEFT",
                newHostParticipantId
            )
        );
    }

    public void publishHostChanged(
        UUID roomId,
        UUID previousHostParticipantId,
        UUID newHostParticipantId
    ) {
        publish(
            roomId,
            "HOST_CHANGED",
            new HostChangedPayload(
                previousHostParticipantId,
                newHostParticipantId
            )
        );
    }

    public void publishMemberReadyUpdated(
        UUID roomId,
        UUID participantId,
        boolean ready,
        boolean allReady
    ) {
        publish(
            roomId,
            "member:ready-updated",
            new MemberReadyPayload(participantId, ready, allReady)
        );
    }
}
