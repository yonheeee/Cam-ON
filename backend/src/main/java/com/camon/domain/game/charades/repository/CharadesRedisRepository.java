package com.camon.domain.game.charades.repository;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class CharadesRedisRepository {

    private static final Set<Integer> ALLOWED_ROUND_COUNTS = Set.of(3, 5, 7, 9);

    private static final String CURRENT_ROUND_FIELD = "current_round";
    private static final String TOTAL_ROUNDS_FIELD = "total_rounds";
    private static final String CURRENT_TURN_FIELD = "current_turn";
    private static final String TOTAL_TURNS_IN_ROUND_FIELD = "total_turns_in_round";
    private static final String TOPIC_ID_FIELD = "topic_id";
    private static final String PRESENTER_ID_FIELD = "presenter_id";
    private static final String MISSION_ID_FIELD = "mission_id";
    private static final String EXPIRES_AT_FIELD = "expires_at";
    private static final String STATUS_FIELD = "status";

    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT =
        new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            redis.call('HSET', KEYS[1],
                'current_round', '0',
                'total_rounds', ARGV[1],
                'current_turn', '0',
                'total_turns_in_round', ARGV[2],
                'topic_id', ARGV[3],
                'status', 'READY')
            for index = 4, #ARGV do
                redis.call('RPUSH', KEYS[2], ARGV[index])
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> OPEN_TURN_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('HSET', KEYS[1],
                'current_round', ARGV[1],
                'current_turn', ARGV[2],
                'presenter_id', ARGV[3],
                'mission_id', ARGV[4],
                'expires_at', ARGV[5],
                'status', 'PLAYING')
            redis.call('SADD', KEYS[2], ARGV[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> TRANSITION_STATUS_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'status', ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CLEAR_SCRIPT =
        new DefaultRedisScript<>("""
            return redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            """, Long.class);

    private final StringRedisTemplate redis;

    public CharadesRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void initialize(
        String roomCode,
        int sessionSeq,
        int totalRounds,
        long topicId,
        List<UUID> presenterOrder
    ) {
        validatePosition(roomCode, sessionSeq);
        validateTotalRounds(totalRounds);
        validateTopicId(topicId);
        validatePresenterOrder(presenterOrder);

        List<String> arguments = new java.util.ArrayList<>(presenterOrder.size() + 3);
        arguments.add(Integer.toString(totalRounds));
        arguments.add(Integer.toString(presenterOrder.size()));
        arguments.add(Long.toString(topicId));
        presenterOrder.forEach(participantId -> arguments.add(participantId.toString()));

        executeRequired(
            INITIALIZE_SCRIPT,
            List.of(
                CharadesRedisKeys.state(roomCode, sessionSeq),
                CharadesRedisKeys.presenterOrder(roomCode, sessionSeq),
                CharadesRedisKeys.usedMissions(roomCode, sessionSeq)
            ),
            arguments.toArray()
        );
    }

    public boolean openTurn(
        String roomCode,
        int sessionSeq,
        int round,
        int turn,
        UUID presenterId,
        long missionId,
        Instant expiresAt
    ) {
        validatePosition(roomCode, sessionSeq);
        if (round < 1) {
            throw new IllegalArgumentException("round must be at least 1");
        }
        if (turn < 1) {
            throw new IllegalArgumentException("turn must be at least 1");
        }
        if (presenterId == null) {
            throw new IllegalArgumentException("presenterId must not be null");
        }
        if (missionId < 1) {
            throw new IllegalArgumentException("missionId must be at least 1");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }

        Long result = redis.execute(
            OPEN_TURN_SCRIPT,
            List.of(
                CharadesRedisKeys.state(roomCode, sessionSeq),
                CharadesRedisKeys.usedMissions(roomCode, sessionSeq)
            ),
            Integer.toString(round),
            Integer.toString(turn),
            presenterId.toString(),
            Long.toString(missionId),
            Long.toString(expiresAt.toEpochMilli())
        );
        return requiredResult(result) == 1L;
    }

    public Optional<CharadesGameState> findState(String roomCode, int sessionSeq) {
        validatePosition(roomCode, sessionSeq);
        Map<Object, Object> values = redis.opsForHash()
            .entries(CharadesRedisKeys.state(roomCode, sessionSeq));
        if (values.isEmpty()) {
            return Optional.empty();
        }

        int currentRound = integerValue(values, CURRENT_ROUND_FIELD);
        int totalRounds = integerValue(values, TOTAL_ROUNDS_FIELD);
        int currentTurn = integerValue(values, CURRENT_TURN_FIELD);
        int totalTurnsInRound = integerValue(values, TOTAL_TURNS_IN_ROUND_FIELD);
        CharadesTurnStatus status = CharadesTurnStatus.valueOf(
            requiredValue(values, STATUS_FIELD)
        );
        return Optional.of(new CharadesGameState(
            currentRound,
            totalRounds,
            currentTurn,
            totalTurnsInRound,
            longValue(values, TOPIC_ID_FIELD),
            uuidValue(values, PRESENTER_ID_FIELD),
            longValue(values, MISSION_ID_FIELD),
            instantValue(values, EXPIRES_AT_FIELD),
            status
        ));
    }

    public List<UUID> getPresenterOrder(String roomCode, int sessionSeq) {
        validatePosition(roomCode, sessionSeq);
        List<String> values = redis.opsForList().range(
            CharadesRedisKeys.presenterOrder(roomCode, sessionSeq),
            0,
            -1
        );
        if (values == null) {
            return List.of();
        }
        return values.stream().map(UUID::fromString).toList();
    }

    public Set<Long> getUsedMissionIds(String roomCode, int sessionSeq) {
        validatePosition(roomCode, sessionSeq);
        Set<String> values = redis.opsForSet().members(
            CharadesRedisKeys.usedMissions(roomCode, sessionSeq)
        );
        if (values == null) {
            return Set.of();
        }
        LinkedHashSet<Long> missionIds = new LinkedHashSet<>();
        values.stream().map(Long::valueOf).sorted().forEach(missionIds::add);
        return Set.copyOf(missionIds);
    }

    public boolean transitionStatus(
        String roomCode,
        int sessionSeq,
        CharadesTurnStatus expected,
        CharadesTurnStatus target
    ) {
        validatePosition(roomCode, sessionSeq);
        if (expected == null || target == null) {
            throw new IllegalArgumentException("statuses must not be null");
        }

        Long result = redis.execute(
            TRANSITION_STATUS_SCRIPT,
            List.of(CharadesRedisKeys.state(roomCode, sessionSeq)),
            expected.name(),
            target.name()
        );
        return requiredResult(result) == 1L;
    }

    public void clear(String roomCode, int sessionSeq) {
        validatePosition(roomCode, sessionSeq);
        executeRequired(
            CLEAR_SCRIPT,
            List.of(
                CharadesRedisKeys.state(roomCode, sessionSeq),
                CharadesRedisKeys.presenterOrder(roomCode, sessionSeq),
                CharadesRedisKeys.usedMissions(roomCode, sessionSeq)
            )
        );
    }

    private static void validatePosition(String roomCode, int sessionSeq) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode must not be blank");
        }
        if (sessionSeq < 1) {
            throw new IllegalArgumentException("sessionSeq must be at least 1");
        }
    }

    private static void validateTotalRounds(int totalRounds) {
        if (!ALLOWED_ROUND_COUNTS.contains(totalRounds)) {
            throw new IllegalArgumentException("totalRounds must be one of 3, 5, 7, 9");
        }
    }

    private static void validateTopicId(long topicId) {
        if (topicId < 1) {
            throw new IllegalArgumentException("topicId must be at least 1");
        }
    }

    private static void validatePresenterOrder(List<UUID> presenterOrder) {
        if (presenterOrder == null || presenterOrder.isEmpty()) {
            throw new IllegalArgumentException("presenterOrder must not be empty");
        }
        if (presenterOrder.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("presenterOrder must not contain null");
        }
        if (new LinkedHashSet<>(presenterOrder).size() != presenterOrder.size()) {
            throw new IllegalArgumentException("presenterOrder must not contain duplicates");
        }
    }

    private long executeRequired(
        DefaultRedisScript<Long> script,
        List<String> keys,
        Object... arguments
    ) {
        return requiredResult(redis.execute(script, keys, arguments));
    }

    private static long requiredResult(Long result) {
        if (result == null) {
            throw new IllegalStateException("Redis charades script returned null");
        }
        return result;
    }

    private static String requiredValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Missing charades state field: " + field);
        }
        return value.toString();
    }

    private static int integerValue(Map<Object, Object> values, String field) {
        return Integer.parseInt(requiredValue(values, field));
    }

    private static UUID uuidValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Long longValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static Instant instantValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.toString()));
    }
}
