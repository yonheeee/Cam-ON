package com.plaiground.global.security.jwt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile({"local", "test"})
    KeyPair localJwtKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            log.warn("Using an ephemeral RSA key pair for the {} profile",
                "local/test");
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA is not available", exception);
        }
    }

    @Bean
    @Profile("!local & !test")
    KeyPair deployedJwtKeyPair(JwtProperties properties) {
        return RsaKeyPairLoader.load(
            properties.privateKeyPath(),
            properties.publicKeyPath()
        );
    }

    @Bean
    JwtTokenProvider jwtTokenProvider(
        KeyPair keyPair,
        JwtProperties properties,
        Clock jwtClock
    ) {
        return new JwtTokenProvider(
            (RSAPrivateKey) keyPair.getPrivate(),
            (RSAPublicKey) keyPair.getPublic(),
            properties,
            jwtClock
        );
    }

    @Bean
    JwtDecoder jwtDecoder(JwtTokenProvider tokenProvider) {
        return tokenProvider::decode;
    }
}
