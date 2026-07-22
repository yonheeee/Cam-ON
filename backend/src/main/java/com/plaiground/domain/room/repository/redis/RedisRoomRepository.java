package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.domain.room.repository.LeaveRoomResult;
import com.plaiground.domain.room.repository.LeaveRoomStatus;
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
    private static final String HOST_PARTICIPANT_ID = "host_participant_id";
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
                'host_participant_id', ARGV[4],
                'max_players', ARGV[5],
                'status', ARGV[6],
                'created_at', ARGV[7])
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> TRY_CREATE_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1
                or redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end

            redis.call('HSET', KEYS[1],
                'room_id', ARGV[1],
                'room_code', ARGV[2],
                'title', ARGV[3],
                'host_participant_id', ARGV[4],
                'max_players', ARGV[5],
                'status', ARGV[6],
                'created_at', ARGV[7])
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('ZADD', KEYS[3], ARGV[12], ARGV[4])
            redis.call('HSET', KEYS[4],
                'nickname', ARGV[8],
                'ready', ARGV[9],
                'connection_status', ARGV[10],
                'joined_at', ARGV[11])
            redis.call('SADD', KEYS[5], ARGV[8])
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

    private static final DefaultRedisScript<String> LEAVE_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'ROOM_NOT_FOUND'
            end

            local participantId = ARGV[1]
            local participantKey = ARGV[2] .. participantId
            if redis.call('ZSCORE', KEYS[2], participantId) == false
                or redis.call('EXISTS', participantKey) == 0 then
                return 'PARTICIPANT_NOT_FOUND'
            end

            local previousHostId = redis.call(
                'HGET', KEYS[1], 'host_participant_id'
            )
            local nickname = redis.call('HGET', participantKey, 'nickname')
            redis.call('ZREM', KEYS[2], participantId)
            if nickname then
                redis.call('SREM', KEYS[3], nickname)
            end
            redis.call('DEL', participantKey)
            redis.call('DEL', ARGV[3] .. participantId .. ':alive')

            if redis.call('ZCARD', KEYS[2]) == 0 then
                local roomCode = redis.call('HGET', KEYS[1], 'room_code')
                if roomCode then
                    redis.call('DEL', ARGV[4] .. roomCode)
                end
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
                return 'ROOM_DELETED|' .. previousHostId
            end

            if previousHostId == participantId then
                local nextHosts = redis.call('ZRANGE', KEYS[2], 0, 0)
                local newHostId = nextHosts[1]
                redis.call('HSET', KEYS[1], 'host_participant_id', newHostId)
                return 'HOST_CHANGED|' .. previousHostId .. '|' .. newHostId
            end

            return 'LEFT|' .. previousHostId
            """, String.class);

    private static final DefaultRedisScript<Long> DELETE_SCRIPT =
        new DefaultRedisScript<>("""
            local members = redis.call('ZRANGE', KEYS[2], 0, -1)
            for _, participantId in ipairs(members) do
                redis.call('DEL', ARGV[1] .. participantId)
                redis.call('DEL', ARGV[2] .. participantId .. ':alive')
            end

            local roomCode = redis.call('HGET', KEYS[1], 'room_code')
            if roomCode then
                redis.call('DEL', ARGV[3] .. roomCode)
            end

            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
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
                RedisRoomKeys.room(room.roomId()),
                RedisRoomKeys.roomCode(room.roomCode())
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
    public boolean tryCreate(Room room, Participant host) {
        Long result = redisTemplate.execute(
            TRY_CREATE_SCRIPT,
            List.of(
                RedisRoomKeys.room(room.roomId()),
                RedisRoomKeys.roomCode(room.roomCode()),
                RedisRoomKeys.participants(room.roomId()),
                RedisRoomKeys.participant(room.roomId(), host.participantId()),
                RedisRoomKeys.nicknames(room.roomId())
            ),
            room.roomId().toString(),
            room.roomCode(),
            room.title(),
            room.hostParticipantId().toString(),
            Integer.toString(room.maxPlayers()),
            room.status().name(),
            room.createdAt().toString(),
            host.nickname(),
            Boolean.toString(host.ready()),
            host.connectionStatus().name(),
            host.joinedAt().toString(),
            Long.toString(host.joinedAt().toEpochMilli())
        );
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public Optional<Room> findById(UUID roomId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
            RedisRoomKeys.room(roomId)
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
    public Optional<Room> findByCode(String roomCode) {
        String roomId = redisTemplate.opsForValue().get(
            RedisRoomKeys.roomCode(roomCode)
        );
        if (roomId == null) {
            return Optional.empty();
        }
        return findById(UUID.fromString(roomId));
    }

    @Override
    public LeaveRoomResult leave(UUID roomId, UUID participantId) {
        String result = redisTemplate.execute(
            LEAVE_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.nicknames(roomId)
            ),
            participantId.toString(),
            RedisRoomKeys.participantPrefix(roomId),
            "session:",
            "room-code:"
        );
        if (result == null) {
            throw new IllegalStateException("Redis returned no leave result");
        }
        String[] values = result.split("\\|");
        LeaveRoomStatus status = LeaveRoomStatus.valueOf(values[0]);
        UUID previousHostId = values.length > 1
            ? UUID.fromString(values[1])
            : null;
        UUID newHostId = values.length > 2
            ? UUID.fromString(values[2])
            : null;
        return new LeaveRoomResult(status, previousHostId, newHostId);
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
        redisTemplate.execute(
            DELETE_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.nicknames(roomId)
            ),
            RedisRoomKeys.participantPrefix(roomId),
            "session:",
            "room-code:"
        );
    }

    private void updateRoomField(UUID roomId, String field, String value) {
        redisTemplate.execute(
            UPDATE_IF_EXISTS_SCRIPT,
            List.of(RedisRoomKeys.room(roomId)),
            field,
            value
        );
    }

    private static String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Redis room field is missing: " + field);
        }
        return value.toString();
    }
}
