package com.plaiground.global.config;

import com.plaiground.dev.DevGuestAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DevGuestAuthFilter devGuestAuthFilter) throws Exception {
        // TODO: Replace permitAll with guest JWT authentication before exposing room APIs.
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().permitAll())
            // TEMP: 게스트 JWT 인증이 붙기 전까지 X-Participant-Id 헤더로 GuestPrincipal을 채운다.
            // 실제 인증 필터가 생기면 이 줄과 com.plaiground.dev 패키지 전체를 지우면 된다.
            .addFilterBefore(devGuestAuthFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
