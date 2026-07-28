package com.camon.domain.session.repository.redis;

import java.util.UUID;

// 게스트 세션과 관련된 Redis keyt 생서 규칙읉 모아둔 클래스
final class RedisGuestSessionKeys {

    private static final String GUEST_SESSION_PREFIX = "guest:session:";

    private RedisGuestSessionKeys() {
    }

    // UUID 받아 Redis Key를 조합해 반환
    static String session(UUID participantId) {
        return GUEST_SESSION_PREFIX + participantId;
    }
}
