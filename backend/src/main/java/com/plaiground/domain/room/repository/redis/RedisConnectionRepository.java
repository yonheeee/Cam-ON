package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.repository.ConnectionRepository;
import com.plaiground.domain.room.repository.HeartbeatRefreshResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisConnectionRepository implements ConnectionRepository {

    private static final long SUCCESS = 0L;
    private static final long ROOM_NOT_FOUND = 1L;
    private static final long PARTICIPANT_NOT_FOUND = 2L;

    private static final DefaultRedisScript<Long> REFRESH_HEARTBEAT_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 1
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0
                or redis.call('EXISTS', KEYS[3]) == 0 then
                return 2
            end

            redis.call('HSET', KEYS[3], 'connection_status', 'CONNECTED')
            redis.call('SET', KEYS[4], ARGV[2], 'PX', ARGV[3])
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisConnectionRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public HeartbeatRefreshResult refreshHeartbeat(
        UUID participantId,
        UUID roomId,
        Duration ttl
    ) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Heartbeat TTL must be positive");
        }
        long ttlMillis = ttl.toMillis();
        if (ttlMillis == 0) {
            throw new IllegalArgumentException(
                "Heartbeat TTL must be at least one millisecond"
            );
        }
        Long result = redisTemplate.execute(
            REFRESH_HEARTBEAT_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participantId),
                RedisRoomKeys.heartbeat(participantId)
            ),
            participantId.toString(),
            roomId.toString(),
            Long.toString(ttlMillis)
        );
        return toRefreshResult(result);
    }

    private static HeartbeatRefreshResult toRefreshResult(Long result) {
        if (result == null) {
            throw new IllegalStateException("Redis returned no heartbeat result");
        }
        if (result == SUCCESS) {
            return HeartbeatRefreshResult.SUCCESS;
        }
        if (result == ROOM_NOT_FOUND) {
            return HeartbeatRefreshResult.ROOM_NOT_FOUND;
        }
        if (result == PARTICIPANT_NOT_FOUND) {
            return HeartbeatRefreshResult.PARTICIPANT_NOT_FOUND;
        }
        throw new IllegalStateException("Unknown Redis heartbeat result: " + result);
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
