package com.camon.global.security.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String issuer,
    String audience,
    Duration accessTokenTtl,
    String privateKeyPath,
    String publicKeyPath
) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience must not be blank");
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access token TTL must be positive");
        }
    }
}
