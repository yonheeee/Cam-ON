package com.camon.domain.room.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record RoomProperties(String frontendBaseUrl) {

    public RoomProperties {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Frontend base URL must not be blank");
        }
    }
}
