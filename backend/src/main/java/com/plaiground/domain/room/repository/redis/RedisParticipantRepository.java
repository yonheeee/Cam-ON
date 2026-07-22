package com.plaiground.domain.room.repository.redis;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.LeaveRoomResult;
import com.plaiground.domain.room.repository.LeaveRoomStatus;
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

    private static final DefaultRedisScript<String> LEAVE_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'ROOM_NOT_FOUND|||false'
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
                return 'PARTICIPANT_NOT_FOUND|||false'
            end

            local previousHost = redis.call('HGET', KEYS[1], 'host_token') or ''
            local nickname = redis.call('HGET', KEYS[3], 'nickname')
            if nickname then
                redis.call('SREM', KEYS[4], nickname)
            end
            redis.call('SREM', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[3], KEYS[5])

            local remaining = redis.call('SMEMBERS', KEYS[2])
            if #remaining == 0 then
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[4])
                return 'SUCCESS|' .. previousHost .. '||true'
            end

            if previousHost ~= ARGV[1] then
                return 'SUCCESS|' .. previousHost .. '|' .. previousHost .. '|false'
            end

            local newHost = nil
            local earliestJoinedAt = nil
            for _, participantId in ipairs(remaining) do
                local joinedAt = tonumber(redis.call(
                    'HGET',
                    ARGV[2] .. participantId,
                    'joined_at'
                ))
                if joinedAt and (
                    earliestJoinedAt == nil
                    or joinedAt < earliestJoinedAt
                    or (joinedAt == earliestJoinedAt and participantId < newHost)
                ) then
                    earliestJoinedAt = joinedAt
                    newHost = participantId
                end
            end

            if not newHost then
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[4])
                return 'SUCCESS|' .. previousHost .. '||true'
            end

            redis.call('HSET', KEYS[1], 'host_token', newHost)
            return 'SUCCESS|' .. previousHost .. '|' .. newHost .. '|false'
            """, String.class);

    private final StringRedisTemplate redisTemplate;

    public RedisParticipantRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public JoinParticipantResult tryAdd(UUID roomId, Participant participant) {
        Long result = redisTemplate.execute(
            TRY_ADD_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participant.participantId()),
                RedisRoomKeys.nicknames(roomId)
            ),
            participant.participantId().toString(),
            participant.nickname(),
            Boolean.toString(participant.ready()),
            participant.connectionStatus().name(),
            Long.toString(participant.joinedAt().toEpochMilli())
        );
        return toJoinResult(result);
    }

    @Override
    public Optional<Participant> findById(UUID roomId, UUID participantId) {
        return findParticipant(roomId, participantId);
    }

    @Override
    public List<Participant> findAll(UUID roomId) {
        Set<String> participantIds = redisTemplate.opsForSet().members(
            RedisRoomKeys.participants(roomId)
        );
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }

        List<Participant> participants = new ArrayList<>(participantIds.size());
        for (String participantId : participantIds) {
            findParticipant(roomId, UUID.fromString(participantId))
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
        redisTemplate.execute(
            RESET_READY_SCRIPT,
            List.of(RedisRoomKeys.participants(roomId)),
            RedisRoomKeys.participantPrefix(roomId)
        );
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
        redisTemplate.execute(
            REMOVE_SCRIPT,
            List.of(
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participantId),
                RedisRoomKeys.nicknames(roomId)
            ),
            participantId.toString()
        );
    }

    @Override
    public LeaveRoomResult leave(UUID roomId, UUID participantId) {
        String result = redisTemplate.execute(
            LEAVE_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participantId),
                RedisRoomKeys.nicknames(roomId),
                RedisRoomKeys.heartbeat(participantId)
            ),
            participantId.toString(),
            RedisRoomKeys.participantPrefix(roomId)
        );
        return toLeaveResult(participantId, result);
    }

    private Optional<Participant> findParticipant(
        UUID roomId,
        UUID participantId
    ) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
            RedisRoomKeys.participant(roomId, participantId)
        );
        if (values.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Participant(
            participantId,
            required(values, NICKNAME),
            Boolean.parseBoolean(required(values, READY)),
            ConnectionStatus.valueOf(required(values, CONNECTION_STATUS)),
            Instant.ofEpochMilli(Long.parseLong(required(values, JOINED_AT)))
        ));
    }

    private void updateParticipantField(
        UUID roomId,
        UUID participantId,
        String field,
        String value
    ) {
        redisTemplate.execute(
            UPDATE_IF_EXISTS_SCRIPT,
            List.of(RedisRoomKeys.participant(roomId, participantId)),
            field,
            value
        );
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

    private static LeaveRoomResult toLeaveResult(
        UUID participantId,
        String result
    ) {
        if (result == null) {
            throw new IllegalStateException("Redis returned no leave result");
        }
        String[] fields = result.split("\\|", -1);
        if (fields.length != 4) {
            throw new IllegalStateException("Invalid Redis leave result: " + result);
        }

        LeaveRoomStatus status = LeaveRoomStatus.valueOf(fields[0]);
        return new LeaveRoomResult(
            status,
            participantId,
            parseUuid(fields[1]),
            parseUuid(fields[2]),
            Boolean.parseBoolean(fields[3])
        );
    }

    private static UUID parseUuid(String value) {
        if (value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
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
