package com.plaiground.domain.room.repository;

import java.time.Duration;
import java.util.UUID;

public interface ConnectionRepository {
    void refreshHeartbeat(
        UUID participantId,
        UUID roomId,
        Duration ttl
    );

    boolean isAlive(UUID participantId);

    void removeHeartbeat(UUID participantId);
}
