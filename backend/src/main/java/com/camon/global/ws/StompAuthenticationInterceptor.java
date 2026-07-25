package com.camon.global.ws;

import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.service.RoomConnectionService;
import com.camon.global.security.GuestJwtAuthenticationConverter;
import com.camon.global.security.GuestPrincipal;
import com.camon.global.security.jwt.JwtTokenProvider;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
public class StompAuthenticationInterceptor implements ChannelInterceptor {

    private static final Pattern ROOM_DESTINATION = Pattern.compile(
        "^/(?:app|topic|user/queue)/rooms/([0-9a-fA-F-]{36})(?:/.*)?$"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final GuestJwtAuthenticationConverter authenticationConverter;
    private final ParticipantRepository participantRepository;
    private final RoomConnectionService connectionService;

    public StompAuthenticationInterceptor(
        JwtTokenProvider jwtTokenProvider,
        GuestJwtAuthenticationConverter authenticationConverter,
        ParticipantRepository participantRepository,
        RoomConnectionService connectionService
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationConverter = authenticationConverter;
        this.participantRepository = participantRepository;
        this.connectionService = connectionService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
            message,
            StompHeaderAccessor.class
        );
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        }
        if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
            authorizeDestination(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null) {
            authorization = accessor.getFirstNativeHeader("accessToken");
        }
        String accessToken = extractBearerToken(authorization);
        AbstractAuthenticationToken authentication = authenticationConverter.convert(
            jwtTokenProvider.decode(accessToken)
        );
        if (authentication == null
            || !(authentication.getPrincipal() instanceof GuestPrincipal principal)) {
            throw new BadCredentialsException("Guest authentication failed");
        }

        UUID roomId = roomId(accessor);
        if (participantRepository.findById(
            roomId,
            principal.participantId()
        ).isEmpty()) {
            throw new AccessDeniedException("Participant does not belong to room");
        }
        accessor.setUser(authentication);
        connectionService.connected(roomId, principal.participantId());
    }

    private void authorizeDestination(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        Matcher matcher = ROOM_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("Unsupported STOMP destination");
        }
        if (!roomId(accessor).equals(UUID.fromString(matcher.group(1)))) {
            throw new AccessDeniedException("Cross-room messaging is not allowed");
        }
        Principal user = accessor.getUser();
        if (user == null) {
            throw new BadCredentialsException("STOMP session is not authenticated");
        }
    }

    private static String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new BadCredentialsException("Access token is required");
        }
        if (authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return authorization;
    }

    private static UUID roomId(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object roomId = attributes == null
            ? null
            : attributes.get(RoomHandshakeInterceptor.ROOM_ID_ATTRIBUTE);
        if (!(roomId instanceof UUID value)) {
            throw new AccessDeniedException("Room handshake information is missing");
        }
        return value;
    }
}
