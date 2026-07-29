package com.camon.domain.game.fetch.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class FetchObjectRedisRepository {

    private static final int MAX_ROUNDS = 10;
    private static final String CURRENT_ROUND_FIELD = "fetch_current_round";
    private static final String STATUS_FIELD = "fetch_status";
    private static final String PLAYING = "PLAYING";
    private static final String ENDED = "ENDED";

    private static final DefaultRedisScript<Long> CLOSE_ROUND_SCRIPT =
        new DefaultRedisScript<>("""
            local currentRound = redis.call('HGET', KEYS[1], 'fetch_current_round')
            if currentRound ~= ARGV[1] then
                return 0
            end
            if redis.call('HGET', KEYS[2], 'status') ~= 'PLAYING' then
                return 0
            end
            if redis.call('HGET', KEYS[2], 'deadline_at') ~= ARGV[2] then
                return 0
            end
            redis.call('HSET', KEYS[2],
                'status', 'ENDED',
                'ended_at', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public FetchObjectRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // CourseRunner가 먼저 만든 공통 session 해시는 보존한다. fetch 전용 키와 필드만 지운다.
    public void clearFetchState(String roomCode, int sessionSeq) {
        List<String> keys = new ArrayList<>();
        keys.add(FetchObjectRedisKeys.participants(roomCode, sessionSeq));
        keys.add(FetchObjectRedisKeys.missionOrder(roomCode, sessionSeq));
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            keys.add(FetchObjectRedisKeys.round(roomCode, sessionSeq, round));
        }
        redis.delete(keys);
        redis.opsForHash().delete(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            CURRENT_ROUND_FIELD,
            STATUS_FIELD
        );
    }

    public void initialize(
        String roomCode,
        int sessionSeq,
        Long gameId,
        int totalRounds,
        List<UUID> participantIds,
        List<Long> missionIds
    ) {
        clearFetchState(roomCode, sessionSeq);

        redis.opsForHash().putAll(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            Map.of(
                "game_id", gameId.toString(),
                "total_rounds", Integer.toString(totalRounds),
                STATUS_FIELD, "READY"
            )
        );
        redis.opsForSet().add(
            FetchObjectRedisKeys.participants(roomCode, sessionSeq),
            participantIds.stream().map(UUID::toString).toArray(String[]::new)
        );
        redis.opsForList().rightPushAll(
            FetchObjectRedisKeys.missionOrder(roomCode, sessionSeq),
            missionIds.stream().map(String::valueOf).toList()
        );
    }

    public Long getMissionIdAt(
        String roomCode,
        int sessionSeq,
        int round
    ) {
        String value = redis.opsForList().index(
            FetchObjectRedisKeys.missionOrder(roomCode, sessionSeq),
            round - 1L
        );
        return value == null ? null : Long.valueOf(value);
    }

    public void openRound(
        String roomCode,
        int sessionSeq,
        int round,
        Long missionId,
        String target,
        Instant startedAt,
        Instant submissionOpensAt,
        Instant deadlineAt
    ) {
        redis.opsForHash().putAll(
            FetchObjectRedisKeys.round(roomCode, sessionSeq, round),
            Map.of(
                "mission_id", missionId.toString(),
                "target", target,
                "started_at", Long.toString(startedAt.toEpochMilli()),
                "submission_opens_at", Long.toString(submissionOpensAt.toEpochMilli()),
                "deadline_at", Long.toString(deadlineAt.toEpochMilli()),
                "status", PLAYING
            )
        );
        redis.opsForHash().putAll(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            Map.of(
                CURRENT_ROUND_FIELD, Integer.toString(round),
                STATUS_FIELD, PLAYING
            )
        );
    }

    public boolean closeRoundIfPlaying(
        String roomCode,
        int sessionSeq,
        int round,
        Instant expectedDeadlineAt,
        Instant endedAt
    ) {
        Long result = redis.execute(
            CLOSE_ROUND_SCRIPT,
            List.of(
                FetchObjectRedisKeys.session(roomCode, sessionSeq),
                FetchObjectRedisKeys.round(roomCode, sessionSeq, round)
            ),
            Integer.toString(round),
            Long.toString(expectedDeadlineAt.toEpochMilli()),
            Long.toString(endedAt.toEpochMilli())
        );
        return result != null && result == 1L;
    }

    public Set<UUID> getParticipants(String roomCode, int sessionSeq) {
        Set<String> values = redis.opsForSet().members(
            FetchObjectRedisKeys.participants(roomCode, sessionSeq)
        );
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UUID> participantIds = new LinkedHashSet<>();
        values.stream()
            .map(UUID::fromString)
            .sorted()
            .forEach(participantIds::add);
        return Set.copyOf(participantIds);
    }

    public void markSessionEnded(String roomCode, int sessionSeq) {
        redis.opsForHash().put(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            STATUS_FIELD,
            ENDED
        );
    }
}
