package com.plaiground.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(12);
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    @Test
    void createsAndVerifiesAccessToken() {
        JwtTokenProvider provider = provider(
            privateKey,
            publicKey,
            properties("plaiground-services"),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID participantId = UUID.randomUUID();

        String accessToken = provider.createAccessToken(participantId);
        Jwt jwt = provider.decode(accessToken);

        assertThat(provider.extractParticipantId(accessToken)).isEqualTo(participantId);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("plaiground-backend");
        assertThat(jwt.getAudience()).containsExactly("plaiground-services");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair anotherKeyPair = generator.generateKeyPair();
        JwtTokenProvider anotherProvider = provider(
            (RSAPrivateKey) anotherKeyPair.getPrivate(),
            (RSAPublicKey) anotherKeyPair.getPublic(),
            properties("plaiground-services"),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        String accessToken = anotherProvider.createAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> provider(
            privateKey,
            publicKey,
            properties("plaiground-services"),
            Clock.systemUTC()
        ).extractParticipantId(accessToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        JwtTokenProvider issuer = provider(
            privateKey,
            publicKey,
            properties("another-service"),
            Clock.fixed(Instant.now(), ZoneOffset.UTC)
        );
        String accessToken = issuer.createAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> provider(
            privateKey,
            publicKey,
            properties("plaiground-services"),
            Clock.systemUTC()
        ).extractParticipantId(accessToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        Clock expiredClock = Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        JwtTokenProvider issuer = provider(
            privateKey,
            publicKey,
            properties("plaiground-services"),
            expiredClock
        );
        String accessToken = issuer.createAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> provider(
            privateKey,
            publicKey,
            properties("plaiground-services"),
            Clock.systemUTC()
        ).extractParticipantId(accessToken)).isInstanceOf(JwtException.class);
    }

    private static JwtTokenProvider provider(
        RSAPrivateKey signingKey,
        RSAPublicKey verificationKey,
        JwtProperties properties,
        Clock clock
    ) {
        return new JwtTokenProvider(signingKey, verificationKey, properties, clock);
    }

    private static JwtProperties properties(String audience) {
        return new JwtProperties("plaiground-backend", audience, TTL);
    }
}
