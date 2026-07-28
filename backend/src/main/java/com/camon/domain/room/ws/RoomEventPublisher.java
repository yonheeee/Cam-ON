package com.camon.domain.room.ws;

import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.ws.payload.HostChangedPayload;
import com.camon.domain.room.ws.payload.MemberConnectionPayload;
import com.camon.domain.room.ws.payload.MemberJoinedPayload;
import com.camon.domain.room.ws.payload.MemberLeftPayload;
import com.camon.domain.room.ws.payload.MemberReadyPayload;
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
            "member:joined",
            new MemberJoinedPayload(participantId, nickname)
        );
    }

    public void publishMemberLeft(
        UUID roomId,
        UUID participantId,
        UUID newHostParticipantId
    ) {
        publishMemberLeft(
            roomId,
            participantId,
            newHostParticipantId,
            "LEFT"
        );
    }

    public void publishToParticipant(
        UUID roomId,
        UUID participantId,
        String eventName,
        Object payload
    ) {
        broadcaster.sendToUser(
            participantId.toString(),
            "/queue/rooms/" + roomId,
            new StompEvent<>(
                UUID.randomUUID(),
                eventName,
                roomId,
                Instant.now(),
                payload
            )
        );
    }

    public void publishMemberLeft(
        UUID roomId,
        UUID participantId,
        UUID newHostParticipantId,
        String reason
    ) {
        publish(
            roomId,
            "member:left",
            new MemberLeftPayload(
                participantId,
                reason,
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
            "host:changed",
            new HostChangedPayload(
                previousHostParticipantId,
                newHostParticipantId
            )
        );
    }

    /**
     * 재접속 유예 동안 다른 참가자 화면에 "연결 끊김"으로 보이게 하기 위한 이벤트.
     * 퇴장(member:left)과는 별개다 — 유예 안에 돌아오면 CONNECTED로 한 번 더 나간다.
     */
    public void publishMemberConnectionChanged(
        UUID roomId,
        UUID participantId,
        ConnectionStatus connectionStatus
    ) {
        publish(
            roomId,
            "member:connection-changed",
            new MemberConnectionPayload(participantId, connectionStatus)
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
