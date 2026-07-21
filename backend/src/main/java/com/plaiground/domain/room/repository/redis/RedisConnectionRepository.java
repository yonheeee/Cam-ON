package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.repository.ConnectionRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisConnectionRepository implements ConnectionRepository {

    private final StringRedisTemplate redisTemplate;

    public RedisConnectionRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void refreshHeartbeat(
        UUID participantId,
        UUID roomId,
        Duration ttl
    ) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Heartbeat TTL must be positive");
        }
        redisTemplate.opsForValue().set(
            RedisRoomKeys.heartbeat(participantId),
            roomId.toString(),
            ttl
        );
    }

    @Override
    public boolean isAlive(UUID participantId) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey(RedisRoomKeys.heartbeat(participantId))
        );
    }

    @Override
    public void removeHeartbeat(UUID participantId) {
        redisTemplate.delete(RedisRoomKeys.heartbeat(participantId));
    }
}
