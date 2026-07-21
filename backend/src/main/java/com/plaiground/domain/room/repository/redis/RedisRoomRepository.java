package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisRoomRepository implements RoomRepository {

    private static final String ROOM_ID = "room_id";
    private static final String ROOM_CODE = "room_code";
    private static final String TITLE = "title";
    private static final String HOST_PARTICIPANT_ID = "host_token";
    private static final String MAX_PLAYERS = "max_players";
    private static final String STATUS = "status";
    private static final String CREATED_AT = "created_at";

    private static final DefaultRedisScript<Long> SAVE_IF_ABSENT_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1
                or redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end

            redis.call('HSET', KEYS[1],
                'room_id', ARGV[1],
                'room_code', ARGV[2],
                'title', ARGV[3],
                'host_token', ARGV[4],
                'max_players', ARGV[5],
                'status', ARGV[6],
                'created_at', ARGV[7])
            redis.call('SET', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> UPDATE_IF_EXISTS_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_SCRIPT =
        new DefaultRedisScript<>("""
            local members = redis.call('SMEMBERS', KEYS[2])
            for _, participantId in ipairs(members) do
                redis.call('DEL', ARGV[1] .. participantId)
                redis.call('DEL', ARGV[2] .. participantId .. ':alive')
            end

            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRoomRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean saveIfAbsent(Room room) {
        Long result = redisTemplate.execute(
            SAVE_IF_ABSENT_SCRIPT,
            List.of(
                RedisRoomKeys.room(room.roomCode()),
                RedisRoomKeys.roomIdIndex(room.roomId())
            ),
            room.roomId().toString(),
            room.roomCode(),
            room.title(),
            room.hostParticipantId().toString(),
            Integer.toString(room.maxPlayers()),
            room.status().name(),
            room.createdAt().toString()
        );
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public Optional<Room> findById(UUID roomId) {
        String roomCode = redisTemplate.opsForValue().get(
            RedisRoomKeys.roomIdIndex(roomId)
        );
        if (roomCode == null) {
            return Optional.empty();
        }
        return findByCode(roomCode);
    }

    @Override
    public Optional<Room> findByCode(String roomCode) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
            RedisRoomKeys.room(roomCode)
        );
        if (values.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Room(
            UUID.fromString(required(values, ROOM_ID)),
            required(values, ROOM_CODE),
            required(values, TITLE),
            UUID.fromString(required(values, HOST_PARTICIPANT_ID)),
            Integer.parseInt(required(values, MAX_PLAYERS)),
            RoomStatus.valueOf(required(values, STATUS)),
            Instant.parse(required(values, CREATED_AT))
        ));
    }

    @Override
    public void updateHost(UUID roomId, UUID hostParticipantId) {
        updateRoomField(
            roomId,
            HOST_PARTICIPANT_ID,
            hostParticipantId.toString()
        );
    }

    @Override
    public void updateStatus(UUID roomId, RoomStatus status) {
        updateRoomField(roomId, STATUS, status.name());
    }

    @Override
    public void delete(UUID roomId) {
        findRoomCode(roomId).ifPresent(roomCode -> redisTemplate.execute(
            DELETE_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomCode),
                RedisRoomKeys.participants(roomCode),
                RedisRoomKeys.nicknames(roomCode),
                RedisRoomKeys.roomIdIndex(roomId)
            ),
            RedisRoomKeys.participantPrefix(roomCode),
            "session:"
        ));
    }

    private void updateRoomField(UUID roomId, String field, String value) {
        findRoomCode(roomId).ifPresent(roomCode -> redisTemplate.execute(
            UPDATE_IF_EXISTS_SCRIPT,
            List.of(RedisRoomKeys.room(roomCode)),
            field,
            value
        ));
    }

    private Optional<String> findRoomCode(UUID roomId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(
            RedisRoomKeys.roomIdIndex(roomId)
        ));
    }

    private static String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Redis room field is missing: " + field);
        }
        return value.toString();
    }
}
