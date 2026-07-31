package com.camon.global.config;

import com.camon.domain.room.config.RoomProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// 프론트(Vite dev 서버, 기본 5173)가 다른 오리진에서 REST를 호출하므로 필요.
// 허용 오리진 목록과 그 근거는 AllowedOrigins에 있다 — WS 핸드셰이크(WebSocketConfig)와 같은
// 목록을 써야 하므로 여기에 따로 적지 않는다.
@Configuration
public class CorsConfig {

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(RoomProperties roomProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
            AllowedOrigins.withDeployOrigin(roomProperties.frontendBaseUrl())
        );
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
