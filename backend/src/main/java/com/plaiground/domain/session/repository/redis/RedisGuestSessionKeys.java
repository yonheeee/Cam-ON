package com.plaiground.domain.session.repository.redis;

import java.util.UUID;

final class RedisGuestSessionKeys {

    private static final String GUEST_SESSION_PREFIX = "guest:session:";

    private RedisGuestSessionKeys() {
    }

    static String session(UUID participantId) {
        return GUEST_SESSION_PREFIX + participantId;
    }
}
