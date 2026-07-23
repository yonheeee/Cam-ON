package com.plaiground.global.config;

import com.plaiground.global.security.GuestJwtAuthenticationConverter;
import com.plaiground.global.security.RestAuthenticationEntryPoint;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        GuestJwtAuthenticationConverter authenticationConverter,
        RestAuthenticationEntryPoint authenticationEntryPoint
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // CorsConfig의 CorsConfigurationSource 빈을 실제로 태우려면 명시적으로 호출해야 함
            // (Spring Security가 자동으로 켜주지 않음) — 안 붙이면 프론트(Vite dev 서버) 요청이
            // preflight 단계에서 다시 막힌다.
            .cors(cors -> {})
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/sessions").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                .authenticationEntryPoint(authenticationEntryPoint));
        return http.build();
    }
}
