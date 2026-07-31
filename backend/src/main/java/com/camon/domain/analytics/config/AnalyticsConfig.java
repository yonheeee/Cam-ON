package com.camon.domain.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfig {
}
