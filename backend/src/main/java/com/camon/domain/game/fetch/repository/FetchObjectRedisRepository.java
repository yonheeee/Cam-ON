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
    private static final List<Long> POINTS_BY_RANK =
        List.of(5L, 4L, 3L, 2L);

    private static final DefaultRedisScript<List> CLAIM_SUBMISSION_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'fetch_status') == false then
                return {1, 0, 0, 0, 0}
            end
            local currentRound = redis.call(
                'HGET', KEYS[1], 'fetch_current_round')
            if currentRound ~= ARGV[1] then
                return {3, 0, 0, 0, 0}
            end
            if redis.call('EXISTS', KEYS[2]) == 0 then
                return {2, 0, 0, 0, 0}
            end
            if redis.call('HGET', KEYS[2], 'status') ~= 'PLAYING' then
                return {4, 0, 0, 0, 0}
            end
            if redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 0 then
                return {7, 0, 0, 0, 0}
            end
            local now = tonumber(ARGV[3])
            local opensAt = tonumber(redis.call(
                'HGET', KEYS[2], 'submission_opens_at'))
            local deadlineAt = tonumber(redis.call(
                'HGET', KEYS[2], 'deadline_at'))
            if now < opensAt then
                return {5, 0, 0, 0, deadlineAt}
            end
            if now > deadlineAt then
                return {6, 0, 0, 0, deadlineAt}
            end
            if redis.call('ZSCORE', KEYS[4], ARGV[2]) ~= false then
                return {8, 0, 0, 0, deadlineAt}
            end
            local rank = redis.call('ZCARD', KEYS[4]) + 1
            local arrivalOrder = now * 10 + rank
            redis.call('ZADD', KEYS[4], arrivalOrder, ARGV[2])
            local participantCount = redis.call('SCARD', KEYS[3])
            return {0, rank, rank, participantCount, deadlineAt}
            """, List.class);

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
            keys.add(
                FetchObjectRedisKeys.submissions(
                    roomCode,
                    sessionSeq,
                    round
                )
            );
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

    public Long getGameId(String roomCode, int sessionSeq) {
        Object value = redis.opsForHash().get(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            "game_id"
        );
        return value == null ? null : Long.valueOf(value.toString());
    }

    public Integer getTotalRounds(String roomCode, int sessionSeq) {
        Object value = redis.opsForHash().get(
            FetchObjectRedisKeys.session(roomCode, sessionSeq),
            "total_rounds"
        );
        return value == null ? null : Integer.valueOf(value.toString());
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
        // 공통 점수 저장(GameScoreService)의 Lua가 room:{code}:session:{seq}:round:{n} 키의
        // 존재를 검증한다 — fetch의 라운드 상태는 :fetch:round:{n}에 있으므로, 공통 키에는
        // 마커만 남겨 검증을 통과시킨다 (닌자 NinjaRedisRepository.startRound와 동일한 패턴).
        // 이게 없으면 라운드 종료 시 ROUND_NOT_FOUND로 점수 저장이 터져 게임이 멈춘다.
        redis.opsForHash().put(
            FetchObjectRedisKeys.session(roomCode, sessionSeq) + ":round:" + round,
            "round",
            Integer.toString(round)
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

    public FetchSubmissionClaimResult claimSubmission(
        String roomCode,
        int sessionSeq,
        int round,
        UUID participantId,
        Instant receivedAt
    ) {
        List<?> result = redis.execute(
            CLAIM_SUBMISSION_SCRIPT,
            List.of(
                FetchObjectRedisKeys.session(roomCode, sessionSeq),
                FetchObjectRedisKeys.round(roomCode, sessionSeq, round),
                FetchObjectRedisKeys.participants(roomCode, sessionSeq),
                FetchObjectRedisKeys.submissions(
                    roomCode,
                    sessionSeq,
                    round
                )
            ),
            Integer.toString(round),
            participantId.toString(),
            Long.toString(receivedAt.toEpochMilli())
        );
        if (result == null || result.size() != 5) {
            throw new IllegalStateException(
                "Redis fetch submission script returned an invalid result"
            );
        }
        int statusCode = numberAt(result, 0).intValue();
        return new FetchSubmissionClaimResult(
            statusFrom(statusCode),
            numberAt(result, 1).intValue(),
            numberAt(result, 2).intValue(),
            numberAt(result, 3).intValue(),
            numberAt(result, 4).longValue()
        );
    }

    public List<UUID> getSubmissionOrder(
        String roomCode,
        int sessionSeq,
        int round
    ) {
        Set<String> values = redis.opsForZSet().range(
            FetchObjectRedisKeys.submissions(roomCode, sessionSeq, round),
            0,
            -1
        );
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(UUID::fromString).toList();
    }

    public long scoreForRank(int rank) {
        if (rank < 1 || rank > POINTS_BY_RANK.size()) {
            return 0L;
        }
        return POINTS_BY_RANK.get(rank - 1);
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

    private static Number numberAt(List<?> values, int index) {
        Object value = values.get(index);
        if (value instanceof Number number) {
            return number;
        }
        return Long.valueOf(value.toString());
    }

    private static FetchSubmissionStatus statusFrom(int code) {
        return switch (code) {
            case 0 -> FetchSubmissionStatus.SUCCESS;
            case 1 -> FetchSubmissionStatus.SESSION_NOT_FOUND;
            case 2 -> FetchSubmissionStatus.ROUND_NOT_FOUND;
            case 3 -> FetchSubmissionStatus.STALE_ROUND;
            case 4 -> FetchSubmissionStatus.ROUND_CLOSED;
            case 5 -> FetchSubmissionStatus.COUNTDOWN_ACTIVE;
            case 6 -> FetchSubmissionStatus.ROUND_EXPIRED;
            case 7 -> FetchSubmissionStatus.PARTICIPANT_NOT_FOUND;
            case 8 -> FetchSubmissionStatus.ALREADY_SUBMITTED;
            default -> throw new IllegalStateException(
                "Unknown fetch submission status: " + code
            );
        };
    }
}
