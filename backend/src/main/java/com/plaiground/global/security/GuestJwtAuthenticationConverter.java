package com.plaiground.global.security;

import com.plaiground.domain.session.repository.SessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class GuestJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

    private final SessionRepository sessionRepository;

    public GuestJwtAuthenticationConverter(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID participantId;
        try {
            participantId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadCredentialsException(
                "JWT subject must be a participant UUID",
                exception
            );
        }

        if (sessionRepository.findByParticipantId(participantId).isEmpty()) {
            throw new BadCredentialsException("Guest session does not exist");
        }

        return UsernamePasswordAuthenticationToken.authenticated(
            new GuestPrincipal(participantId),
            null,
            List.of()
        );
    }
}
