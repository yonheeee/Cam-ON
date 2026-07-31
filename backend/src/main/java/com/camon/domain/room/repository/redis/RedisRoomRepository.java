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
    // 코스에서 진행 중인 위치(1부터). 게임 도메인이 이 값으로 room:{code}:session:{seq} 키를 조립한다.
    private static final String CURRENT_SESSION_SEQ = "current_session_seq";

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
                'joined_at', ARGV[10],
                'in_lobby', ARGV[11])
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

    // 현재 seq가 기대값일 때만 올린다 — 인터미션 타이머와 방장의 "바로 시작"이 겹쳐 같은
    // 게임을 두 번 여는 것을 막는 진행 권한 획득 지점.
    private static final DefaultRedisScript<Long> ADVANCE_SEQ_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
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
                -- 코스 항목과 코스 누적 점수도 방 생명주기에 묶인 데이터라 함께 지운다.
                -- (참가자 키들과 달리 roomCode로 키를 잡으므로 여기서 따로 조립해야 한다.)
                local courseLength =
                    tonumber(redis.call('HGET', KEYS[1], 'course_length') or '0')
                for i = 1, courseLength do
                    redis.call('DEL', ARGV[5] .. roomCode .. ':course:' .. i)
                end
                redis.call('DEL', ARGV[5] .. roomCode .. ':course:totals')
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
            Long.toString(host.joinedAt().toEpochMilli()),
            Boolean.toString(host.inLobby())
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
            // 방 생성 시엔 이 필드를 쓰지 않는다(코스가 아직 없음) — 첫 세션이 열릴 때 1로 기록된다.
            // 없으면 1로 읽어 "아직 첫 게임" 취급.
            optionalInt(values, CURRENT_SESSION_SEQ, 1),
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
    public void updateCurrentSessionSeq(UUID roomId, int sessionSeq) {
        updateRoomField(roomId, CURRENT_SESSION_SEQ, Integer.toString(sessionSeq));
    }

    @Override
    public boolean tryAdvanceSessionSeq(UUID roomId, int fromSeq, int toSeq) {
        Long result = redisTemplate.execute(
            ADVANCE_SEQ_SCRIPT,
            List.of(RedisRoomKeys.room(roomId)),
            CURRENT_SESSION_SEQ,
            Integer.toString(fromSeq),
            Integer.toString(toSeq)
        );
        return Long.valueOf(1L).equals(result);
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
            RedisRoomKeys.participantRoomPrefix(),
            // TODO: room:{code}:session:{seq}... (진행 중 게임 상태) 는 여전히 남는다 —
            // 게임 도메인이 소유한 키라 개수를 여기서 알 수 없다. 세션 종료 시 각 게임이
            // 지우거나(닌자 clearSession/몸으로말해요 clear는 이미 그렇게 한다) 별도 스윕이 필요.
            "room:"
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

    // 이 필드가 없던 시점에 만들어진 방(코스 기능 배포 전에 열려 있던 방)도 그대로 읽히게
    // 기본값을 준다 — Redis 스키마 마이그레이션 수단이 없으므로 읽는 쪽이 흡수한다.
    private static int optionalInt(
        Map<Object, Object> values,
        String field,
        int defaultValue
    ) {
        Object value = values.get(field);
        return value == null ? defaultValue : Integer.parseInt(value.toString());
    }

    private static String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Redis room field is missing: " + field);
        }
        return value.toString();
    }
}
