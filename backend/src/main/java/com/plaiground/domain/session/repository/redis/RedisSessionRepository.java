package com.plaiground.domain.session.repository.redis;

import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.repository.SessionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionRepository implements SessionRepository {

    private static final String PARTICIPANT_ID = "participant_id";
    private static final String NICKNAME = "nickname";
    private static final String CREATED_AT = "created_at";

    private final StringRedisTemplate redisTemplate;

    public RedisSessionRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(GuestSession session) {
        redisTemplate.opsForHash().putAll(
            RedisGuestSessionKeys.session(session.participantId()),
            Map.of(
                PARTICIPANT_ID, session.participantId().toString(),
                NICKNAME, session.nickname(),
                CREATED_AT, session.createdAt().toString()
            )
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
