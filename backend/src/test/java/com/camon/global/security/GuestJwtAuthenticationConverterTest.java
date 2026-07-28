package com.camon.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.camon.domain.session.domain.GuestSession;
import com.camon.domain.session.repository.SessionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

class GuestJwtAuthenticationConverterTest {

    @Test
    void authenticatesWhenRedisSessionExists() {
        SessionRepository repository = mock(SessionRepository.class);
        UUID participantId = UUID.randomUUID();
        when(repository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(
                participantId,
                "플레이어1",
                Instant.now()
            ))
        );
        GuestJwtAuthenticationConverter converter =
            new GuestJwtAuthenticationConverter(repository);

        Authentication authentication = converter.convert(jwt(participantId.toString()));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal())
            .isEqualTo(new GuestPrincipal(participantId));
    }

    @Test
    void rejectsValidJwtWhenRedisSessionDoesNotExist() {
        SessionRepository repository = mock(SessionRepository.class);
        UUID participantId = UUID.randomUUID();
        when(repository.findByParticipantId(participantId))
            .thenReturn(Optional.empty());
        GuestJwtAuthenticationConverter converter =
            new GuestJwtAuthenticationConverter(repository);

        assertThatThrownBy(() -> converter.convert(jwt(participantId.toString())))
            .isInstanceOf(BadCredentialsException.class);
    }

    private static Jwt jwt(String subject) {
        Instant now = Instant.now();
        return new Jwt(
            "token",
            now,
            now.plusSeconds(3_600),
            Map.of("alg", "RS256"),
            Map.of("sub", subject)
        );
    }
}
