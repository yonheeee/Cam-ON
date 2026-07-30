package com.camon.global.config;

import java.util.ArrayList;
import java.util.List;

// REST(CORS)와 WS 핸드셰이크가 허용하는 오리진 목록은 반드시 같아야 한다. 예전엔 두 설정
// 클래스에 따로 적혀 있었고 실제로 갈라져서, LAN IP(폰 테스트 등)로 접속하면 REST는 통과하는데
// WS만 403으로 막혔다 — 방 생성·준비·게임 시작 요청은 전부 성공하는데 game:started가 도달하지
// 못해 "게임은 시작됐지만 화면이 안 넘어가는" 형태로 나타나서 원인을 찾기 어려웠다.
// 그래서 목록의 출처를 이 클래스 하나로 둔다.
final class AllowedOrigins {

    // 팀원들이 각자 다른 네트워크(와이파이 LAN, 휴대폰 핫스팟)에서 접속해 테스트하는데 핫스팟
    // 사설망 대역이 통신사/기기마다 달라서(아이폰 172.20.10.x, 안드로이드 192.168.43.x,
    // Windows 모바일 핫스팟 192.168.137.x) 특정 대역만 허용하면 계속 두더지잡기가 된다.
    // 실제 보안이 필요 없는 로컬 개발용이라 호스트는 통째로 허용하고 포트(Vite dev 5173)만 고정한다.
    // localhost는 포트를 바꿔 띄우는 경우가 있어 포트를 열어둔다.
    private static final List<String> LOCAL_DEV_PATTERNS = List.of(
        "http://localhost:*",
        "http://*:5173"
    );

    private AllowedOrigins() {
    }

    /**
     * 로컬 개발 오리진 + 배포 오리진. 배포 도메인이 바뀌어도 자바 코드는 건드리지 않고
     * FRONTEND_BASE_URL 환경변수만 바꾸면 된다.
     */
    static List<String> withDeployOrigin(String deployOrigin) {
        List<String> patterns = new ArrayList<>(LOCAL_DEV_PATTERNS);
        patterns.add(deployOrigin);
        return List.copyOf(patterns);
    }
}
