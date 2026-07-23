package com.plaiground.global.ws;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class RoomHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ROOM_ID_ATTRIBUTE = "roomId";

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
        String path = request.getURI().getPath();
        String roomId = path.substring(path.lastIndexOf('/') + 1);
        attributes.put(ROOM_ID_ATTRIBUTE, UUID.fromString(roomId));
        return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
    }
}
