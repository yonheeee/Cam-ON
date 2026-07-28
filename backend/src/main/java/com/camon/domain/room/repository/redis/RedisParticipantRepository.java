package com.camon.domain.room.repository.redis;

import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.repository.JoinParticipantResult;
import com.camon.domain.room.repository.KickParticipantResult;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.ReadyUpdateResult;
import com.camon.domain.room.repository.ReadyUpdateStatus;
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
    private static final long BANNED = 6L;

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
            if redis.call('EXISTS', KEYS[5]) == 1 then
                return 5
            end
            if redis.call('SISMEMBER', KEYS[6], ARGV[1]) == 1 then
                return 6
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
            redis.call('SET', KEYS[5], ARGV[6])
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
            if redis.call('GET', KEYS[4]) == ARGV[2] then
                redis.call('DEL', KEYS[4])
            end
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

    private static final DefaultRedisScript<String> UPDATE_READY_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'ROOM_NOT_FOUND|false|false'
            end
            if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
                return 'ROOM_ALREADY_STARTED|false|false'
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0
                or redis.call('EXISTS', KEYS[3]) == 0 then
                return 'PARTICIPANT_NOT_FOUND|false|false'
            end

            redis.call('HSET', KEYS[3], 'ready', ARGV[2])

            local allReady = true
            local members = redis.call('SMEMBERS', KEYS[2])
            if #members == 0 then
                allReady = false
            end
            for _, memberId in ipairs(members) do
                local memberReady = redis.call(
                    'HGET', ARGV[3] .. memberId, 'ready'
                )
                if memberReady ~= 'true' then
                    allReady = false
                    break
                end
            end

            return 'SUCCESS|' .. ARGV[2] .. '|'
                .. tostring(allReady)
            """, String.class);

    private static final DefaultRedisScript<Long> UPDATE_IF_EXISTS_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    // 검증부터 제거·banned 등록까지 한 번에 — 조회와 제거 사이에 방장 위임/퇴장이 끼어들 수 없다.
    // 방장은 강퇴 대상이 될 수 없으므로(요청자=방장, 자기 자신 금지) 위임·방 삭제 분기가 없다.
    private static final DefaultRedisScript<String> KICK_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'ROOM_NOT_FOUND'
            end
            if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
                return 'ROOM_ALREADY_STARTED'
            end
            if redis.call('HGET', KEYS[1], 'host_participant_id') ~= ARGV[2] then
                return 'NOT_HOST'
            end
            if ARGV[1] == ARGV[2] then
                return 'SELF_KICK'
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
                return 'PARTICIPANT_NOT_FOUND'
            end

            local nickname = redis.call('HGET', KEYS[3], 'nickname')
            if nickname then
                redis.call('SREM', KEYS[4], nickname)
            end
            redis.call('SREM', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[3], KEYS[5])
            if redis.call('GET', KEYS[6]) == ARGV[3] then
                redis.call('DEL', KEYS[6])
            end
            redis.call('SADD', KEYS[7], ARGV[1])
            return 'SUCCESS'
            """, String.class);

    private static final DefaultRedisScript<String> LEAVE_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 'ROOM_NOT_FOUND|||false'
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
                return 'PARTICIPANT_NOT_FOUND|||false'
            end
            if ARGV[4] == 'true' and redis.call('EXISTS', KEYS[5]) == 1 then
                return 'HEARTBEAT_ACTIVE|||false'
            end

            local previousHost = redis.call('HGET', KEYS[1], 'host_participant_id') or ''
            local roomCode = redis.call('HGET', KEYS[1], 'room_code')
            local nickname = redis.call('HGET', KEYS[3], 'nickname')
            if nickname then
                redis.call('SREM', KEYS[4], nickname)
            end
            redis.call('SREM', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[3], KEYS[5])
            if redis.call('GET', KEYS[6]) == ARGV[6] then
                redis.call('DEL', KEYS[6])
            end

            local remaining = redis.call('SMEMBERS', KEYS[2])
            if #remaining == 0 then
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[4], KEYS[7])
                if roomCode then
                    redis.call('DEL', ARGV[5] .. roomCode)
                end
                return 'SUCCESS|' .. previousHost .. '||true'
            end

            if previousHost ~= ARGV[1] then
                return 'SUCCESS|' .. previousHost .. '|' .. previousHost .. '|false'
            end

            local newHost = nil
            local earliestParticipant = nil
            local earliestJoinedAt = nil
            local earliestAliveJoinedAt = nil
            for _, participantId in ipairs(remaining) do
                local joinedAt = tonumber(redis.call(
                    'HGET',
                    ARGV[2] .. participantId,
                    'joined_at'
                ))
                if joinedAt and (
                    earliestJoinedAt == nil
                    or joinedAt < earliestJoinedAt
                    or (
                        joinedAt == earliestJoinedAt
                        and participantId < earliestParticipant
                    )
                ) then
                    earliestJoinedAt = joinedAt
                    earliestParticipant = participantId
                end
                if joinedAt
                    and redis.call(
                        'EXISTS',
                        ARGV[3] .. participantId .. ':alive'
                    ) == 1
                    and (
                        earliestAliveJoinedAt == nil
                        or joinedAt < earliestAliveJoinedAt
                        or (
                            joinedAt == earliestAliveJoinedAt
                            and participantId < newHost
                        )
                    ) then
                    earliestAliveJoinedAt = joinedAt
                    newHost = participantId
                end
            end

            if ARGV[4] ~= 'true' or not newHost then
                newHost = earliestParticipant
            end

            if not newHost then
                redis.call('DEL', KEYS[1], KEYS[2], KEYS[4], KEYS[7])
                if roomCode then
                    redis.call('DEL', ARGV[5] .. roomCode)
                end
                return 'SUCCESS|' .. previousHost .. '||true'
            end

            redis.call('HSET', KEYS[1], 'host_participant_id', newHost)
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
                RedisRoomKeys.nicknames(roomId),
                RedisRoomKeys.participantRoom(participant.participantId()),
                RedisRoomKeys.banned(roomId)
            ),
            participant.participantId().toString(),
            participant.nickname(),
            Boolean.toString(participant.ready()),
            participant.connectionStatus().name(),
            Long.toString(participant.joinedAt().toEpochMilli()),
            roomId.toString()
        );
        return toJoinResult(result);
    }

    @Override
    public Optional<Participant> findById(UUID roomId, UUID participantId) {
        return findParticipant(roomId, participantId);
    }

    @Override
    public Optional<UUID> findCurrentRoomId(UUID participantId) {
        String roomId = redisTemplate.opsForValue().get(
            RedisRoomKeys.participantRoom(participantId)
        );
        return roomId == null
            ? Optional.empty()
            : Optional.of(UUID.fromString(roomId));
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
    public ReadyUpdateResult updateReady(
        UUID roomId,
        UUID participantId,
        boolean ready
    ) {
        String result = redisTemplate.execute(
            UPDATE_READY_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participantId)
            ),
            participantId.toString(),
            Boolean.toString(ready),
            RedisRoomKeys.participantPrefix(roomId)
        );
        return toReadyUpdateResult(result);
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
                RedisRoomKeys.nicknames(roomId),
                RedisRoomKeys.participantRoom(participantId)
            ),
            participantId.toString(),
            roomId.toString()
        );
    }

    @Override
    public KickParticipantResult kick(
        UUID roomId,
        UUID requesterId,
        UUID targetId
    ) {
        String result = redisTemplate.execute(
            KICK_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, targetId),
                RedisRoomKeys.nicknames(roomId),
                RedisRoomKeys.heartbeat(targetId),
                RedisRoomKeys.participantRoom(targetId),
                RedisRoomKeys.banned(roomId)
            ),
            targetId.toString(),
            requesterId.toString(),
            roomId.toString()
        );
        if (result == null) {
            throw new IllegalStateException("Redis returned no kick result");
        }
        return KickParticipantResult.valueOf(result);
    }

    @Override
    public LeaveRoomResult leave(UUID roomId, UUID participantId) {
        return leave(roomId, participantId, false);
    }

    @Override
    public LeaveRoomResult leaveIfHeartbeatExpired(
        UUID roomId,
        UUID participantId
    ) {
        return leave(roomId, participantId, true);
    }

    private LeaveRoomResult leave(
        UUID roomId,
        UUID participantId,
        boolean heartbeatMustBeExpired
    ) {
        String result = redisTemplate.execute(
            LEAVE_SCRIPT,
            List.of(
                RedisRoomKeys.room(roomId),
                RedisRoomKeys.participants(roomId),
                RedisRoomKeys.participant(roomId, participantId),
                RedisRoomKeys.nicknames(roomId),
                RedisRoomKeys.heartbeat(participantId),
                RedisRoomKeys.participantRoom(participantId),
                RedisRoomKeys.banned(roomId)
            ),
            participantId.toString(),
            RedisRoomKeys.participantPrefix(roomId),
            RedisRoomKeys.heartbeatPrefix(),
            Boolean.toString(heartbeatMustBeExpired),
            RedisRoomKeys.roomCodePrefix(),
            roomId.toString()
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
        if (result == BANNED) {
            return JoinParticipantResult.BANNED;
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

    private static ReadyUpdateResult toReadyUpdateResult(String result) {
        if (result == null) {
            throw new IllegalStateException("Redis returned no ready result");
        }
        String[] fields = result.split("\\|", -1);
        if (fields.length != 3) {
            throw new IllegalStateException(
                "Invalid Redis ready result: " + result
            );
        }
        return new ReadyUpdateResult(
            ReadyUpdateStatus.valueOf(fields[0]),
            Boolean.parseBoolean(fields[1]),
            Boolean.parseBoolean(fields[2])
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
