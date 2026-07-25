package com.camon.domain.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.session.domain.GuestSession;
import com.camon.domain.session.dto.CreateSessionRequest;
import com.camon.domain.session.dto.CreateSessionResponse;
import com.camon.domain.session.repository.SessionRepository;
import com.camon.global.security.jwt.JwtTokenProvider;
import com.camon.global.security.jwt.JwtTokenProvider.IssuedAccessToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionServiceTest {

    @Test
    void createsRedisSessionAndReturnsAccessToken() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        Instant expiresAt = now.plusSeconds(43_200);
        when(tokenProvider.issueAccessToken(any())).thenReturn(
            new IssuedAccessToken("access-token", expiresAt)
        );
        SessionService service = new SessionService(
            sessionRepository,
            tokenProvider,
            Clock.fixed(now, ZoneOffset.UTC)
        );

        CreateSessionResponse response = service.createSession(
            new CreateSessionRequest("플레이어1")
        );

        ArgumentCaptor<GuestSession> sessionCaptor =
            ArgumentCaptor.forClass(GuestSession.class);
        verify(sessionRepository).save(sessionCaptor.capture(), any(Instant.class));
        GuestSession savedSession = sessionCaptor.getValue();
        assertThat(savedSession.participantId()).isEqualTo(response.participantId());
        assertThat(savedSession.nickname()).isEqualTo("플레이어1");
        assertThat(savedSession.createdAt()).isEqualTo(now);
        assertThat(response.nickname()).isEqualTo("플레이어1");
        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(sessionRepository).save(savedSession, expiresAt);
        verify(tokenProvider).issueAccessToken(savedSession.participantId());
    }
}
