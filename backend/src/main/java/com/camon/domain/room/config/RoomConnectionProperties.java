package com.camon.domain.room.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.connection")
public record RoomConnectionProperties(Duration heartbeatTtl) {

    public RoomConnectionProperties {
        if (heartbeatTtl == null
            || heartbeatTtl.isZero()
            || heartbeatTtl.isNegative()) {
            throw new IllegalArgumentException(
                "Heartbeat TTL must be positive"
            );
        }
    }
}
