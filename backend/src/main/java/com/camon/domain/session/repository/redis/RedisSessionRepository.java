package com.camon.domain.session.repository.redis;

import com.camon.domain.session.domain.GuestSession;
import com.camon.domain.session.repository.SessionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionRepository implements SessionRepository {

    private static final String PARTICIPANT_ID = "participant_id";
    private static final String NICKNAME = "nickname";
    private static final String CREATED_AT = "created_at";

    private static final DefaultRedisScript<Long> SAVE_SCRIPT =
        new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1],
                'participant_id', ARGV[1],
                'nickname', ARGV[2],
                'created_at', ARGV[3])
            redis.call('PEXPIREAT', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisSessionRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(GuestSession session, Instant expiresAt) {
        if (!expiresAt.isAfter(session.createdAt())) {
            throw new IllegalArgumentException(
                "Guest session expiration must be after creation"
            );
        }
        redisTemplate.execute(
            SAVE_SCRIPT,
            java.util.List.of(RedisGuestSessionKeys.session(session.participantId())),
            session.participantId().toString(),
            session.nickname(),
            session.createdAt().toString(),
            Long.toString(expiresAt.toEpochMilli())
        );
    }

    @Override
    public Optional<GuestSession> findByParticipantId(UUID participantId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
            RedisGuestSessionKeys.session(participantId)
        );
        if (values.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new GuestSession(
            UUID.fromString(required(values, PARTICIPANT_ID)),
            required(values, NICKNAME),
            Instant.parse(required(values, CREATED_AT))
        ));
    }

    @Override
    public void delete(UUID participantId) {
        redisTemplate.delete(RedisGuestSessionKeys.session(participantId));
    }

    private static String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException(
                "Redis guest session field is missing: " + field
            );
        }
        return value.toString();
    }
}
