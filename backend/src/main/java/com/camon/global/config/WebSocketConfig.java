package com.camon.global.config;

import com.camon.domain.room.config.RoomProperties;
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
    private final RoomProperties roomProperties;

    public WebSocketConfig(
        RoomHandshakeInterceptor roomHandshakeInterceptor,
        @Lazy StompAuthenticationInterceptor authenticationInterceptor,
        RoomProperties roomProperties
    ) {
        this.roomHandshakeInterceptor = roomHandshakeInterceptor;
        this.authenticationInterceptor = authenticationInterceptor;
        this.roomProperties = roomProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 허용 오리진은 REST(CorsConfig)와 같은 목록을 쓴다 — 갈라지면 REST만 통과하고 WS가
        // 막혀서 상태 변경은 되는데 전파가 안 되는 형태로 깨진다(AllowedOrigins 주석 참고).
        registry.addEndpoint("/ws/rooms/{roomId}")
            .addInterceptors(roomHandshakeInterceptor)
            .setAllowedOriginPatterns(
                AllowedOrigins
                    .withDeployOrigin(roomProperties.frontendBaseUrl())
                    .toArray(String[]::new)
            );
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }
}
