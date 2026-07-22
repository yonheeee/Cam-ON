package com.plaiground.global.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// 프론트(Vite dev 서버, 기본 5173)가 다른 오리진에서 REST를 호출하므로 필요.
// 로컬 개발용 오리진만 허용 — 배포 환경 오리진은 나중에 추가.
// setAllowedOriginPatterns를 쓰는 이유: 팀원들이 각자 다른 네트워크(와이파이 LAN, 휴대폰
// 핫스팟 등)로 접속해서 테스트하는데, 핫스팟 사설망 대역이 통신사/기기마다 다 달라서
// (예: 아이폰 핫스팟은 172.20.10.x, 안드로이드는 보통 192.168.43.x, Windows 모바일 핫스팟은
// 192.168.137.x) 특정 대역만 허용하면 계속 두더지잡기가 된다. 실제 보안이 필요 없는 로컬
// 개발용 서버라 포트(5173)만 고정하고 호스트는 통째로 허용한다.
@Configuration
public class CorsConfig {

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
            "http://*:5173"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
