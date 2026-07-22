package com.plaiground.domain.game.ninja.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

// room:{code}:session:{seq} 계열 키만 다룬다 — room/course/session 자체의 생성·진행은 이 도메인 책임이 아니고,
// 세션 하나가 열려 있다는 전제 하에 닌자 전용 필드(alive_players/eliminated/player_hp/round 등)만 읽고 쓴다.
@Repository
public class NinjaRedisRepository {

    private static final String CLOSED_FIELD = "closed";
    private static final String SKILL_ID_FIELD = "skill_id";
    private static final String STARTED_AT_FIELD = "started_at";
    private static final String CURRENT_ROUND_FIELD = "current_round";
    private static final String TOTAL_ROUNDS_FIELD = "total_rounds";
    private static final String ATTACKER_TOKEN_FIELD = "attacker_token";
    private static final String TARGET_TOKEN_FIELD = "target_token";
    private static final String JUDGED_AT_FIELD = "judged_at";

    private final StringRedisTemplate redis;

    public NinjaRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String sessionKey(String roomCode, int seq) {
        return "room:%s:session:%d".formatted(roomCode, seq);
    }

    private String skillOrderKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":skill_order";
    }

    private String roundKey(String roomCode, int seq, int round) {
        return sessionKey(roomCode, seq) + ":round:" + round;
    }

    private String attackKey(String roomCode, int seq, int round) {
        return roundKey(roomCode, seq, round) + ":attack";
    }

    private String aliveKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":alive_players";
    }

    private String eliminatedKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":eliminated";
    }

    private String hpKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":player_hp";
    }

    // --- 세션 준비 ---

    public void saveSkillOrder(String roomCode, int seq, List<Long> shuffledSkillIds) {
        String key = skillOrderKey(roomCode, seq);
        redis.delete(key);
        List<String> values = shuffledSkillIds.stream().map(String::valueOf).toList();
        redis.opsForList().rightPushAll(key, values);
    }

    public Long getSkillIdForRound(String roomCode, int seq, int round) {
        String key = skillOrderKey(roomCode, seq);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            return null;
        }
        int index = (int) ((round - 1) % size);
        String value = redis.opsForList().index(key, index);
        return value == null ? null : Long.valueOf(value);
    }

    public void initAlivePlayers(String roomCode, int seq, Set<String> tokens) {
        redis.opsForSet().add(aliveKey(roomCode, seq), tokens.toArray(String[]::new));
    }

    public void initPlayerHp(String roomCode, int seq, Set<String> tokens, int initialHp) {
        String key = hpKey(roomCode, seq);
        for (String token : tokens) {
            redis.opsForHash().put(key, token, String.valueOf(initialHp));
        }
    }

    public void setCurrentRound(String roomCode, int seq, int round) {
        redis.opsForHash().put(sessionKey(roomCode, seq), CURRENT_ROUND_FIELD, String.valueOf(round));
    }

    public Integer getCurrentRound(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), CURRENT_ROUND_FIELD);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    public Integer getTotalRounds(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), TOTAL_ROUNDS_FIELD);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    // --- 라운드 진행 ---

    public void openRound(String roomCode, int seq, int round, Long skillId, Instant startedAt) {
        String key = roundKey(roomCode, seq, round);
        redis.opsForHash().put(key, SKILL_ID_FIELD, String.valueOf(skillId));
        redis.opsForHash().put(key, STARTED_AT_FIELD, String.valueOf(startedAt.getEpochSecond()));
    }

    public Long getRoundSkillId(String roomCode, int seq, int round) {
        Object value = redis.opsForHash().get(roundKey(roomCode, seq, round), SKILL_ID_FIELD);
        return value == null ? null : Long.valueOf(value.toString());
    }

    public boolean roundExists(String roomCode, int seq, int round) {
        return Boolean.TRUE.equals(redis.hasKey(roundKey(roomCode, seq, round)));
    }

    // 라운드 종료(대상 지정 완료 vs 타임아웃) 경합 해소용 락. 먼저 도착한 호출만 true를 받는다.
    public boolean closeRound(String roomCode, int seq, int round, String reason) {
        return Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(roundKey(roomCode, seq, round), CLOSED_FIELD, reason)
        );
    }

    public boolean isRoundClosed(String roomCode, int seq, int round) {
        return redis.opsForHash().hasKey(roundKey(roomCode, seq, round), CLOSED_FIELD);
    }

    // --- 공격권/대상 선점 (동시 완성 예외 처리) ---

    public boolean claimAttacker(String roomCode, int seq, int round, String token) {
        return Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(attackKey(roomCode, seq, round), ATTACKER_TOKEN_FIELD, token)
        );
    }

    public void recordAttackSkill(String roomCode, int seq, int round, Long skillId, Instant judgedAt) {
        String key = attackKey(roomCode, seq, round);
        redis.opsForHash().put(key, SKILL_ID_FIELD, String.valueOf(skillId));
        redis.opsForHash().put(key, JUDGED_AT_FIELD, String.valueOf(judgedAt.getEpochSecond()));
    }

    public String getAttacker(String roomCode, int seq, int round) {
        Object value = redis.opsForHash().get(attackKey(roomCode, seq, round), ATTACKER_TOKEN_FIELD);
        return value == null ? null : value.toString();
    }

    public boolean claimTarget(String roomCode, int seq, int round, String token) {
        return Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(attackKey(roomCode, seq, round), TARGET_TOKEN_FIELD, token)
        );
    }

    // --- 생존/HP ---

    public Set<String> getAlivePlayers(String roomCode, int seq) {
        return redis.opsForSet().members(aliveKey(roomCode, seq));
    }

    public boolean isAlive(String roomCode, int seq, String token) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(aliveKey(roomCode, seq), token));
    }

    public long decrementHp(String roomCode, int seq, String token, int damage) {
        return redis.opsForHash().increment(hpKey(roomCode, seq), token, -damage);
    }

    public Map<Object, Object> getAllHp(String roomCode, int seq) {
        return redis.opsForHash().entries(hpKey(roomCode, seq));
    }

    public void eliminate(String roomCode, int seq, String token, Instant eliminatedAt) {
        redis.opsForSet().remove(aliveKey(roomCode, seq), token);
        redis.opsForZSet().add(eliminatedKey(roomCode, seq), token, eliminatedAt.toEpochMilli());
    }

    // 늦게 탈락한 순서(= 높은 점수 = 높은 순위)대로 반환.
    public List<String> getEliminatedOrderDesc(String roomCode, int seq) {
        Set<String> members = redis.opsForZSet().reverseRange(eliminatedKey(roomCode, seq), 0, -1);
        return members == null ? List.of() : List.copyOf(members);
    }
}
