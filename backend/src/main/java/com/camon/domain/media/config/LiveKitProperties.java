package com.camon.domain.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(
    String url,
    String apiKey,
    String apiSecret
) {
}
