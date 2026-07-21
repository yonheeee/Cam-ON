package com.plaiground.global.security.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public class JwtTokenProvider {

    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
        "invalid_token",
        "The required audience is missing",
        null
    );

    private final JwtProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public JwtTokenProvider(
        RSAPrivateKey privateKey,
        RSAPublicKey publicKey,
        JwtProperties properties,
        Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
        this.encoder = createEncoder(privateKey, publicKey);
        this.decoder = createDecoder(publicKey, properties);
    }

    public String createAccessToken(UUID participantId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .type("JWT")
            .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(participantId.toString())
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public UUID extractParticipantId(String accessToken) {
        Jwt jwt = decode(accessToken);
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new JwtException("JWT subject must be a participant UUID", exception);
        }
    }

    Jwt decode(String accessToken) {
        return decoder.decode(accessToken);
    }

    private static JwtEncoder createEncoder(
        RSAPrivateKey privateKey,
        RSAPublicKey publicKey
    ) {
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    private static NimbusJwtDecoder createDecoder(
        RSAPublicKey publicKey,
        JwtProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
            JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
            jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            issuerValidator,
            audienceValidator
        ));
        return decoder;
    }
}
