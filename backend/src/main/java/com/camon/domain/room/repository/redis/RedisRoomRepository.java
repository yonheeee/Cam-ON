package com.camon.domain.room.repository.redis;

import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.repository.RoomRepository;
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
                'host_participant_id', ARGV[3],
                'max_players', ARGV[4],
                'status', ARGV[5],
                'created_at', ARGV[6])
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
                'host_participant_id', ARGV[3],
                'max_players', ARGV[4],
                'status', ARGV[5],
                'created_at', ARGV[6])
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('SADD', KEYS[3], ARGV[3])
            redis.call('HSET', KEYS[4],
                'nickname', ARGV[7],
                'ready', ARGV[8],
                'connection_status', ARGV[9],
                'joined_at', ARGV[10])
            redis.call('SADD', KEYS[5], ARGV[7])
            redis.call('SET', KEYS[6], ARGV[1])
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
            local roomId = redis.call('HGET', KEYS[1], 'room_id')
            for _, participantId in ipairs(members) do
                redis.call('DEL', ARGV[1] .. participantId)
                redis.call('DEL', ARGV[2] .. participantId .. ':alive')
                local participantRoomKey = ARGV[4] .. participantId
                if redis.call('GET', participantRoomKey) == roomId then
                    redis.call('DEL', participantRoomKey)
                end
            end

            local roomCode = redis.call('HGET', KEYS[1], 'room_code')
            if roomCode then
                redis.call('DEL', ARGV[3] .. roomCode)
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
                RedisRoomKeys.room(room.roomId()),
                RedisRoomKeys.roomCode(room.roomCode())
            ),
            room.roomId().toString(),
            room.roomCode(),
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
                RedisRoomKeys.nicknames(room.roomId()),
                RedisRoomKeys.participantRoom(host.participantId())
            ),
            room.roomId().toString(),
            room.roomCode(),
            room.hostParticipantId().toString(),
            Integer.toString(room.maxPlayers()),
            room.status().name(),
            room.createdAt().toString(),
            host.nickname(),
            Boolean.toString(host.ready()),
            host.connectionStatus().name(),
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
            UUID.fromString(required(values, HOST_PARTICIPANT_ID)),
            Integer.parseInt(required(values, MAX_PLAYERS)),
            RoomStatus.valueOf(required(values, STATUS)),
            // TODO: 코스/세션 도메인이 생기면 room 해시에 current_session_seq 필드를 실제로
            // 저장/조회하도록 채워야 한다. 지금은 코스 개념 자체가 없어서 항상 첫 세션(1)로 취급.
            1,
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
                RedisRoomKeys.nicknames(roomId),
                // 강퇴 명단도 방 생명주기에 묶인다 — 방이 사라지면 같이 지운다.
                RedisRoomKeys.banned(roomId)
            ),
            RedisRoomKeys.participantPrefix(roomId),
            "session:",
            "room-code:",
            RedisRoomKeys.participantRoomPrefix()
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
