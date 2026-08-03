package com.camon.domain.analytics.service;

import com.camon.domain.analytics.config.AnalyticsProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsKeyHasher {

    private final SecretKeySpec secretKey;

    public AnalyticsKeyHasher(AnalyticsProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().isBlank()) {
            throw new IllegalArgumentException("app.analytics.hmac-secret must not be blank");
        }
        this.secretKey = new SecretKeySpec(
            properties.hmacSecret().getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
        );
    }

    public String hash(UUID value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            return HexFormat.of().formatHex(
                mac.doFinal(value.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Cannot initialize analytics HMAC", exception);
        }
    }
}
