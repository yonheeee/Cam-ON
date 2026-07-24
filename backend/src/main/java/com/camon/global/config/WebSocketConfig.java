package com.camon.global.config;

import com.camon.global.ws.RoomHandshakeInterceptor;
import com.camon.global.ws.StompAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RoomHandshakeInterceptor roomHandshakeInterceptor;
    private final StompAuthenticationInterceptor authenticationInterceptor;

    public WebSocketConfig(
        RoomHandshakeInterceptor roomHandshakeInterceptor,
        @Lazy StompAuthenticationInterceptor authenticationInterceptor
    ) {
        this.roomHandshakeInterceptor = roomHandshakeInterceptor;
        this.authenticationInterceptor = authenticationInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/rooms/{roomId}")
            .addInterceptors(roomHandshakeInterceptor)
            .setAllowedOriginPatterns("http://localhost:*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }
}
