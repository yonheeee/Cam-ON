package com.camon.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.camon.domain.room.config.RoomProperties;
import org.junit.jupiter.api.Test;

class CorsConfigTest {

    private static final String DEPLOY_ORIGIN = "https://i15b110.p.ssafy.io";

    // REST가 허용하는데 WS가 막는 오리진이 생기면, 상태 변경 요청은 전부 성공하면서 전파만
    // 조용히 끊긴다(게임은 시작됐는데 화면이 안 넘어감). 두 설정이 같은 목록을 쓰는지 고정한다.
    @Test
    void restAndWebSocketShareTheSameAllowedOrigins() {
        RoomProperties roomProperties = mock(RoomProperties.class);
        when(roomProperties.frontendBaseUrl()).thenReturn(DEPLOY_ORIGIN);

        var configurationSource = new CorsConfig()
            .corsConfigurationSource(roomProperties);
        var patterns = configurationSource
            .getCorsConfigurations()
            .get("/**")
            .getAllowedOriginPatterns();

        assertThat(patterns)
            .isEqualTo(AllowedOrigins.withDeployOrigin(DEPLOY_ORIGIN));
    }

    // LAN IP로 접속하는 경우(폰 카메라 테스트 등)가 실제 사용 방식이라 반드시 포함돼야 한다.
    @Test
    void allowsLanIpOriginOnTheViteDevPort() {
        assertThat(AllowedOrigins.withDeployOrigin(DEPLOY_ORIGIN))
            .contains("http://*:5173", "http://localhost:*", DEPLOY_ORIGIN);
    }
}
