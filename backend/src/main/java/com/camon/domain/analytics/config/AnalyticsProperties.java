package com.camon.domain.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
    String hmacSecret,
    String appVersion,
    String experimentVersion
) {
}
