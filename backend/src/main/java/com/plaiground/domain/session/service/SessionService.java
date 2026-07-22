package com.plaiground.domain.session.service;

import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.dto.CreateSessionRequest;
import com.plaiground.domain.session.dto.CreateSessionResponse;
import com.plaiground.domain.session.repository.SessionRepository;
import com.plaiground.global.security.jwt.JwtTokenProvider;
import com.plaiground.global.security.jwt.JwtTokenProvider.IssuedAccessToken;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public SessionService(
        SessionRepository sessionRepository,
        JwtTokenProvider jwtTokenProvider,
        Clock jwtClock
    ) {
        this.sessionRepository = sessionRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clock = jwtClock;
    }

    public CreateSessionResponse createSession(CreateSessionRequest request) {
        UUID participantId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        GuestSession session = new GuestSession(
            participantId,
            request.nickname(),
            createdAt
        );
        IssuedAccessToken accessToken =
            jwtTokenProvider.issueAccessToken(participantId);

        sessionRepository.save(session, accessToken.expiresAt());

        return new CreateSessionResponse(
            participantId,
            session.nickname(),
            accessToken.value()
        );
    }
}
