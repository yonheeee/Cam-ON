package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.ParticipantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisParticipantRepository implements ParticipantRepository {

    private static final String NICKNAME = "nickname";
    private static final String READY = "ready";
    private static final String CONNECTION_STATUS = "connection_status";
    private static final String JOINED_AT = "joined_at";

    private static final long SUCCESS = 0L;
    private static final long ROOM_NOT_FOUND = 1L;
    private static final long ROOM_FULL = 2L;
    private static final long ROOM_ALREADY_STARTED = 3L;
    private static final long NICKNAME_DUPLICATED = 4L;
    private static final long ALREADY_JOINED = 5L;

    private static final DefaultRedisScript<Long> TRY_ADD_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 1
            end
            if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
                return 3
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                return 5
            end
            local maxPlayers = tonumber(redis.call('HGET', KEYS[1], 'max_players'))
            if not maxPlayers or redis.call('SCARD', KEYS[2]) >= maxPlayers then
                return 2
            end
            if redis.call('SISMEMBER', KEYS[4], ARGV[2]) == 1 then
                return 4
            end

            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3],
                'nickname', ARGV[2],
                'ready', ARGV[3],
                'connection_status', ARGV[4],
                'joined_at', ARGV[5])
            redis.call('SADD', KEYS[4], ARGV[2])
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_SCRIPT =
        new DefaultRedisScript<>("""
            local nickname = redis.call('HGET', KEYS[2], 'nickname')
            if nickname then
                redis.call('SREM', KEYS[3], nickname)
            end
            redis.call('SREM', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RESET_READY_SCRIPT =
        new DefaultRedisScript<>("""
            local members = redis.call('SMEMBERS', KEYS[1])
            for _, participantId in ipairs(members) do
                local participantKey = ARGV[1] .. participantId
                if redis.call('EXISTS', participantKey) == 1 then
                    redis.call('HSET', participantKey, 'ready', 'false')
                end
            end
            return #members
            """, Long.class);

    private static final DefaultRedisScript<Long> UPDATE_IF_EXISTS_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisParticipantRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public JoinParticipantResult tryAdd(UUID roomId, Participant participant) {
        Optional<String> roomCode = findRoomCode(roomId);
        if (roomCode.isEmpty()) {
            return JoinParticipantResult.ROOM_NOT_FOUND;
        }

        String code = roomCode.get();
        Long result = redisTemplate.execute(
            TRY_ADD_SCRIPT,
            List.of(
                RedisRoomKeys.room(code),
                RedisRoomKeys.participants(code),
                RedisRoomKeys.participant(code, participant.participantId()),
                RedisRoomKeys.nicknames(code)
            ),
            participant.participantId().toString(),
            participant.nickname(),
            Boolean.toString(participant.ready()),
            participant.connectionStatus().name(),
            participant.joinedAt().toString()
        );
        return toJoinResult(result);
    }

    @Override
    public Optional<Participant> findById(UUID roomId, UUID participantId) {
        return findRoomCode(roomId).flatMap(roomCode ->
            findParticipant(roomCode, participantId)
        );
    }

    @Override
    public List<Participant> findAll(UUID roomId) {
        Optional<String> roomCode = findRoomCode(roomId);
        if (roomCode.isEmpty()) {
            return List.of();
        }

        Set<String> participantIds = redisTemplate.opsForSet().members(
            RedisRoomKeys.participants(roomCode.get())
        );
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }

        List<Participant> participants = new ArrayList<>(participantIds.size());
        for (String participantId : participantIds) {
            findParticipant(roomCode.get(), UUID.fromString(participantId))
                .ifPresent(participants::add);
        }
        participants.sort(Comparator.comparing(Participant::joinedAt));
        return List.copyOf(participants);
    }

    @Override
    public void updateReady(UUID roomId, UUID participantId, boolean ready) {
        updateParticipantField(
            roomId,
            participantId,
            READY,
            Boolean.toString(ready)
        );
    }

    @Override
    public void resetAllReady(UUID roomId) {
        findRoomCode(roomId).ifPresent(roomCode -> redisTemplate.execute(
            RESET_READY_SCRIPT,
            List.of(RedisRoomKeys.participants(roomCode)),
            RedisRoomKeys.participantPrefix(roomCode)
        ));
    }

    @Override
    public void updateConnectionStatus(
        UUID roomId,
        UUID participantId,
        ConnectionStatus status
    ) {
        updateParticipantField(
            roomId,
            participantId,
            CONNECTION_STATUS,
            status.name()
        );
    }

    @Override
    public void remove(UUID roomId, UUID participantId) {
        findRoomCode(roomId).ifPresent(roomCode -> redisTemplate.execute(
            REMOVE_SCRIPT,
            List.of(
                RedisRoomKeys.participants(roomCode),
                RedisRoomKeys.participant(roomCode, participantId),
                RedisRoomKeys.nicknames(roomCode)
            ),
            participantId.toString()
        ));
    }

    private Optional<Participant> findParticipant(
        String roomCode,
        UUID participantId
    ) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
            RedisRoomKeys.participant(roomCode, participantId)
        );
        if (values.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Participant(
            participantId,
            required(values, NICKNAME),
            Boolean.parseBoolean(required(values, READY)),
            ConnectionStatus.valueOf(required(values, CONNECTION_STATUS)),
            Instant.parse(required(values, JOINED_AT))
        ));
    }

    private void updateParticipantField(
        UUID roomId,
        UUID participantId,
        String field,
        String value
    ) {
        findRoomCode(roomId).ifPresent(roomCode -> redisTemplate.execute(
            UPDATE_IF_EXISTS_SCRIPT,
            List.of(RedisRoomKeys.participant(roomCode, participantId)),
            field,
            value
        ));
    }

    private Optional<String> findRoomCode(UUID roomId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(
            RedisRoomKeys.roomIdIndex(roomId)
        ));
    }

    private static JoinParticipantResult toJoinResult(Long result) {
        if (result == null) {
            throw new IllegalStateException("Redis returned no join result");
        }
        if (result == SUCCESS) {
            return JoinParticipantResult.SUCCESS;
        }
        if (result == ROOM_NOT_FOUND) {
            return JoinParticipantResult.ROOM_NOT_FOUND;
        }
        if (result == ROOM_FULL) {
            return JoinParticipantResult.ROOM_FULL;
        }
        if (result == ROOM_ALREADY_STARTED) {
            return JoinParticipantResult.ROOM_ALREADY_STARTED;
        }
        if (result == NICKNAME_DUPLICATED) {
            return JoinParticipantResult.NICKNAME_DUPLICATED;
        }
        if (result == ALREADY_JOINED) {
            return JoinParticipantResult.ALREADY_JOINED;
        }
        throw new IllegalStateException("Unknown Redis join result: " + result);
    }

    private static String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException(
                "Redis participant field is missing: " + field
            );
        }
        return value.toString();
    }
}
