package com.plaiground.dev;

import com.plaiground.global.security.GuestPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// TEMP — 실제 게스트 세션/JWT 인증이 붙기 전까지 X-Participant-Id 헤더 값을 그대로 GuestPrincipal로
// 신뢰한다. 방/세션 도메인이 완성되고 진짜 인증 필터가 생기면 이 클래스와 SecurityConfig의
// addFilterBefore(devGuestAuthFilter, ...) 줄, dev 패키지 전체를 삭제하면 된다.
// 컨트롤러 쪽은 @AuthenticationPrincipal GuestPrincipal만 알고 어떻게 채워지는지 모르므로
// 실제 인증으로 교체돼도 컨트롤러/서비스 코드는 손댈 필요 없다.
@Component
public class DevGuestAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Participant-Id";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String value = request.getHeader(HEADER);
        if (value != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                GuestPrincipal principal = new GuestPrincipal(UUID.fromString(value));
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of())
                );
            } catch (IllegalArgumentException ignored) {
                // 헤더가 UUID 형식이 아니면 인증 없이 통과 — 컨트롤러/서비스에서 principal 관련 오류로 드러남.
            }
        }
        filterChain.doFilter(request, response);
    }
}
