package com.camon.domain.game.common.repository.redis;

import com.camon.domain.game.common.repository.GameResultRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.RoomRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisGameResultRepository implements GameResultRepository {

    private static final long SUCCESS = 1L;
    private static final long ALREADY_SAVED = 0L;
    private static final long ROOM_NOT_FOUND = -1L;
    private static final long SESSION_NOT_FOUND = -2L;
    private static final long ROUND_NOT_FOUND = -3L;

    private static final DefaultRedisScript<Long> SAVE_ROUND_RESULTS_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end
            if redis.call('EXISTS', KEYS[2]) == 0 then
                return -2
            end
            if redis.call('EXISTS', KEYS[3]) == 0 then
                return -3
            end
            if redis.call('EXISTS', KEYS[4]) == 1 then
                return 0
            end

            for index = 1, #ARGV, 2 do
                local participantId = ARGV[index]
                local score = ARGV[index + 1]
                redis.call('HSET', KEYS[4], participantId, score)
                redis.call('HINCRBY', KEYS[5], participantId, score)
                redis.call('HINCRBY', KEYS[6], participantId, score)
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RoomRepository roomRepository;

    public RedisGameResultRepository(
        StringRedisTemplate redisTemplate,
        RoomRepository roomRepository
    ) {
        this.redisTemplate = redisTemplate;
        this.roomRepository = roomRepository;
    }

    @Override
    public SaveRoundResult saveRoundResults(
        UUID roomId,
        int sessionSeq,
        int round,
        Map<UUID, Long> scores
    ) {
        validatePosition(sessionSeq, round);
        validateScores(scores);

        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            return SaveRoundResult.ROOM_NOT_FOUND;
        }

        List<String> arguments = new ArrayList<>(scores.size() * 2);
        scores.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                arguments.add(entry.getKey().toString());
                arguments.add(Long.toString(entry.getValue()));
            });

        Long result = redisTemplate.execute(
            SAVE_ROUND_RESULTS_SCRIPT,
            List.of(
                RedisGameResultKeys.room(roomId),
                RedisGameResultKeys.session(room.roomCode(), sessionSeq),
                RedisGameResultKeys.round(room.roomCode(), sessionSeq, round),
                RedisGameResultKeys.roundResults(room.roomCode(), sessionSeq, round),
                RedisGameResultKeys.sessionTotals(room.roomCode(), sessionSeq),
                RedisGameResultKeys.courseTotals(room.roomCode())
            ),
            arguments.toArray()
        );
        if (result == null) {
            throw new IllegalStateException("Redis game result script returned null");
        }
        return mapResult(result);
    }

    @Override
    public Map<UUID, Long> findRoundResults(
        UUID roomId,
        int sessionSeq,
        int round
    ) {
        validatePosition(sessionSeq, round);
        return findScores(roomId, roomCode ->
            RedisGameResultKeys.roundResults(roomCode, sessionSeq, round)
        );
    }

    @Override
    public Map<UUID, Long> findSessionTotals(UUID roomId, int sessionSeq) {
        if (sessionSeq < 1) {
            throw new IllegalArgumentException("sessionSeq must be at least 1");
        }
        return findScores(roomId, roomCode ->
            RedisGameResultKeys.sessionTotals(roomCode, sessionSeq)
        );
    }

    @Override
    public Map<UUID, Long> findCourseTotals(UUID roomId) {
        return findScores(roomId, RedisGameResultKeys::courseTotals);
    }

    private Map<UUID, Long> findScores(
        UUID roomId,
        KeyFactory keyFactory
    ) {
        return roomRepository.findById(roomId)
            .map(room -> readScores(keyFactory.create(room.roomCode())))
            .orElseGet(Map::of);
    }

    private Map<UUID, Long> readScores(String key) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        LinkedHashMap<UUID, Long> scores = new LinkedHashMap<>();
        values.entrySet().stream()
            .map(entry -> Map.entry(
                UUID.fromString(entry.getKey().toString()),
                Long.parseLong(entry.getValue().toString())
            ))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> scores.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(scores);
    }

    private static SaveRoundResult mapResult(long result) {
        if (result == SUCCESS) {
            return SaveRoundResult.SUCCESS;
        }
        if (result == ALREADY_SAVED) {
            return SaveRoundResult.ALREADY_SAVED;
        }
        if (result == ROOM_NOT_FOUND) {
            return SaveRoundResult.ROOM_NOT_FOUND;
        }
        if (result == SESSION_NOT_FOUND) {
            return SaveRoundResult.SESSION_NOT_FOUND;
        }
        if (result == ROUND_NOT_FOUND) {
            return SaveRoundResult.ROUND_NOT_FOUND;
        }
        throw new IllegalStateException("Unknown Redis game result: " + result);
    }

    private static void validatePosition(int sessionSeq, int round) {
        if (sessionSeq < 1) {
            throw new IllegalArgumentException("sessionSeq must be at least 1");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round must be at least 1");
        }
    }

    private static void validateScores(Map<UUID, Long> scores) {
        if (scores == null || scores.isEmpty()) {
            throw new IllegalArgumentException("scores must not be empty");
        }
        if (scores.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getValue() == null
        )) {
            throw new IllegalArgumentException("scores must not contain null");
        }
    }

    @FunctionalInterface
    private interface KeyFactory {
        String create(String roomCode);
    }
}
