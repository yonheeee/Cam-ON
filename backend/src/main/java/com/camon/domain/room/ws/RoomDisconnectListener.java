package com.camon.domain.room.ws;

import com.camon.domain.room.service.RoomConnectionService;
import com.camon.global.security.GuestPrincipal;
import com.camon.global.ws.RoomHandshakeInterceptor;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class RoomDisconnectListener {

    private final RoomConnectionService connectionService;

    public RoomDisconnectListener(RoomConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(
            event.getMessage()
        );
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Principal user = accessor.getUser();
        if (attributes == null || user == null) {
            return;
        }
        Object roomId = attributes.get(
            RoomHandshakeInterceptor.ROOM_ID_ATTRIBUTE
        );
        if (!(roomId instanceof UUID value)) {
            return;
        }
        if (!(user instanceof org.springframework.security.core.Authentication auth)
            || !(auth.getPrincipal() instanceof GuestPrincipal principal)) {
            return;
        }
        connectionService.disconnected(value, principal.participantId());
    }
}
