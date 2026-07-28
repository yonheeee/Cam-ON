package com.camon.domain.game.ninja.repository;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

// room:{code}:session:{seq} 계열 키만 다룬다 — room/course/session 자체의 생성·진행은 이 도메인 책임이 아니고,
// 세션 하나가 열려 있다는 전제 하에 닌자 전용 상태만 읽고 쓴다.
//
// 2단계 구조:
//   round(판, r)  = 전원 풀피로 시작해 최후 1인이 남을 때까지 벌이는 한 판. 판마다 HP/생존/탈락순이 리셋된다.
//     exchange(교환, e) = 판 안에서 "스킬콤보→공격권→대상→데미지" 1회 교환. 최후 1인이 남을 때까지 반복.
// 판별 상태(alive/hp/eliminated)는 round:{r} 밑에, 교환별 상태(skill/attack/closed)는 round:{r}:ex:{e} 밑에 둔다.
@Slf4j
@Repository
public class NinjaRedisRepository {

    private static final String CLOSED_FIELD = "closed";
    private static final String SKILL_ID_FIELD = "skill_id";
    private static final String STARTED_AT_FIELD = "started_at";
    private static final String CURRENT_ROUND_FIELD = "current_round";
    private static final String CURRENT_EXCHANGE_FIELD = "current_exchange";
    private static final String TOTAL_ROUNDS_FIELD = "total_rounds";
    private static final String GAME_ID_FIELD = "game_id";
    private static final String SKILL_CURSOR_FIELD = "skill_cursor";
    private static final String ATTACKER_TOKEN_FIELD = "attacker_token";
    private static final String TARGET_TOKEN_FIELD = "target_token";
    private static final String JUDGED_AT_FIELD = "judged_at";
    private static final String DAMAGE_FIELD = "damage";
    private static final String HP_AFTER_FIELD = "hp_after";
    private static final String ELIMINATED_FIELD = "eliminated";

    // 인터미션(교환/판 사이 대기) 상태를 세션 해시에 둔다 — 진행/전환을 클라가 아니라 서버 기준
    // 시각으로 판단하게 하는 값들.
    private static final String PHASE_FIELD = "phase";
    private static final String EFFECT_UNTIL_FIELD = "effect_until";
    private static final String NEXT_ROUND_AT_FIELD = "next_round_at";

    private final StringRedisTemplate redis;

    public NinjaRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String sessionKey(String roomCode, int seq) {
        return "room:%s:session:%d".formatted(roomCode, seq);
    }

    // 이 세션(room:{code}:session:{seq}) 밑에 걸린 키를 전부 지운다 — round:{r}:ex:{e}처럼 번호가
    // 안 정해진 키까지 다 정리해야 해서 정확한 키 목록 대신 접두사로 통째로 찾아 지운다.
    // 참가자 몇 명 규모의 로컬/데모 환경이라 KEYS(운영 비권장 O(N))를 써도 문제없다 — 세션 시작 시 한 번만 호출.
    public void clearSession(String roomCode, int seq) {
        Set<String> keys = redis.keys(sessionKey(roomCode, seq) + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.info("[Repository] clearSession : roomCode={} seq={} {}개 키 삭제", roomCode, seq, keys.size());
        }
    }

    private String skillOrderKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":skill_order";
    }

    private String participantsKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":participants";
    }

    private String boutKey(String roomCode, int seq, int round) {
        return sessionKey(roomCode, seq) + ":round:" + round;
    }

    private String exchangeKey(String roomCode, int seq, int round, int exchange) {
        return boutKey(roomCode, seq, round) + ":ex:" + exchange;
    }

    private String attackKey(String roomCode, int seq, int round, int exchange) {
        return exchangeKey(roomCode, seq, round, exchange) + ":attack";
    }

    private String aliveKey(String roomCode, int seq, int round) {
        return boutKey(roomCode, seq, round) + ":alive";
    }

    private String eliminatedKey(String roomCode, int seq, int round) {
        return boutKey(roomCode, seq, round) + ":eliminated";
    }

    private String hpKey(String roomCode, int seq, int round) {
        return boutKey(roomCode, seq, round) + ":hp";
    }

    private String finalRankingKey(String roomCode, int seq) {
        return sessionKey(roomCode, seq) + ":final_ranking";
    }

    // --- 세션 준비 ---

    public void saveParticipants(String roomCode, int seq, Set<String> tokens) {
        redis.opsForSet().add(participantsKey(roomCode, seq), tokens.toArray(String[]::new));
    }

    public Set<String> getParticipants(String roomCode, int seq) {
        return redis.opsForSet().members(participantsKey(roomCode, seq));
    }

    public void saveSkillOrder(String roomCode, int seq, List<Long> shuffledSkillIds) {
        String key = skillOrderKey(roomCode, seq);
        redis.delete(key);
        List<String> values = shuffledSkillIds.stream().map(String::valueOf).toList();
        redis.opsForList().rightPushAll(key, values);
    }

    private Long skillOrderSize(String roomCode, int seq) {
        return redis.opsForList().size(skillOrderKey(roomCode, seq));
    }

    private Long skillOrderAt(String roomCode, int seq, long index) {
        String value = redis.opsForList().index(skillOrderKey(roomCode, seq), index);
        return value == null ? null : Long.valueOf(value);
    }

    // 교환이 열릴 때마다 커서를 하나 올려 셔플된 스킬 순서에서 다음 스킬을 뽑는다 — skill 개수를
    // 넘어가면 다시 앞에서부터 순환한다(교환 수는 판마다 다르고 상한이 없어 순환이 자연스럽다).
    public Long drawNextSkill(String roomCode, int seq) {
        Long size = skillOrderSize(roomCode, seq);
        if (size == null || size == 0) {
            return null;
        }
        Long cursor = redis.opsForHash().increment(sessionKey(roomCode, seq), SKILL_CURSOR_FIELD, 1L);
        long index = (cursor - 1) % size;
        return skillOrderAt(roomCode, seq, index);
    }

    // 아직 안 뽑힌 "바로 다음" 스킬(예고용). 커서를 올리지 않는다.
    public Long peekNextSkill(String roomCode, int seq) {
        Long size = skillOrderSize(roomCode, seq);
        if (size == null || size == 0) {
            return null;
        }
        Object cursorValue = redis.opsForHash().get(sessionKey(roomCode, seq), SKILL_CURSOR_FIELD);
        long cursor = cursorValue == null ? 0L : Long.parseLong(cursorValue.toString());
        return skillOrderAt(roomCode, seq, cursor % size);
    }

    public void setCurrentRound(String roomCode, int seq, int round) {
        redis.opsForHash().put(sessionKey(roomCode, seq), CURRENT_ROUND_FIELD, String.valueOf(round));
    }

    public Integer getCurrentRound(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), CURRENT_ROUND_FIELD);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    public void setCurrentExchange(String roomCode, int seq, int exchange) {
        redis.opsForHash().put(sessionKey(roomCode, seq), CURRENT_EXCHANGE_FIELD, String.valueOf(exchange));
    }

    public Integer getCurrentExchange(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), CURRENT_EXCHANGE_FIELD);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    public Integer getTotalRounds(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), TOTAL_ROUNDS_FIELD);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    // 원래는 세션이 열릴 때 course:{seq}.round_count를 그대로 복사해오는 값(room/course 도메인 책임) —
    // 그 흐름이 아직 없어서 지금은 이 메서드로 직접 채운다.
    public void setTotalRounds(String roomCode, int seq, int totalRounds) {
        redis.opsForHash().put(sessionKey(roomCode, seq), TOTAL_ROUNDS_FIELD, String.valueOf(totalRounds));
    }

    public void setGameId(String roomCode, int seq, Long gameId) {
        redis.opsForHash().put(sessionKey(roomCode, seq), GAME_ID_FIELD, String.valueOf(gameId));
    }

    public Long getGameId(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), GAME_ID_FIELD);
        return value == null ? null : Long.valueOf(value.toString());
    }

    // --- 진행 단계(phase) / 인터미션 타이밍 ---

    // 정상 진행(손동작 입력을 받는 교환 진행 중)으로 되돌린다. 이전 인터미션의 이펙트/카운트다운
    // 시각을 지워서, 재접속 스냅샷이 지나간 인터미션을 잘못 복구하지 않게 한다.
    public void enterRound(String roomCode, int seq) {
        String key = sessionKey(roomCode, seq);
        redis.opsForHash().put(key, PHASE_FIELD, NinjaPhase.ROUND.name());
        redis.opsForHash().delete(key, EFFECT_UNTIL_FIELD, NEXT_ROUND_AT_FIELD);
    }

    // 인터미션 진입: 이펙트 종료 시각(effectUntil, 타임아웃은 null)과 다음 교환/판 시작 시각
    // (nextRoundAt, 게임 종료 결정타면 null)을 서버 기준으로 박아둔다.
    public void enterIntermission(String roomCode, int seq, Instant effectUntil, Instant nextRoundAt) {
        String key = sessionKey(roomCode, seq);
        redis.opsForHash().put(key, PHASE_FIELD, NinjaPhase.INTERMISSION.name());
        putInstantOrDelete(key, EFFECT_UNTIL_FIELD, effectUntil);
        putInstantOrDelete(key, NEXT_ROUND_AT_FIELD, nextRoundAt);
    }

    public void enterEnded(String roomCode, int seq) {
        String key = sessionKey(roomCode, seq);
        redis.opsForHash().put(key, PHASE_FIELD, NinjaPhase.ENDED.name());
        redis.opsForHash().delete(key, EFFECT_UNTIL_FIELD, NEXT_ROUND_AT_FIELD);
    }

    public NinjaPhase getPhase(String roomCode, int seq) {
        Object value = redis.opsForHash().get(sessionKey(roomCode, seq), PHASE_FIELD);
        return value == null ? null : NinjaPhase.valueOf(value.toString());
    }

    public Instant getEffectUntil(String roomCode, int seq) {
        return getInstant(sessionKey(roomCode, seq), EFFECT_UNTIL_FIELD);
    }

    public Instant getNextRoundAt(String roomCode, int seq) {
        return getInstant(sessionKey(roomCode, seq), NEXT_ROUND_AT_FIELD);
    }

    private void putInstantOrDelete(String key, String field, Instant value) {
        if (value == null) {
            redis.opsForHash().delete(key, field);
        } else {
            redis.opsForHash().put(key, field, String.valueOf(value.toEpochMilli()));
        }
    }

    private Instant getInstant(String key, String field) {
        Object value = redis.opsForHash().get(key, field);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.toString()));
    }

    // --- 판(bout) 시작: HP/생존/탈락순 리셋 ---

    public void startBout(String roomCode, int seq, int round, Set<String> tokens, int initialHp) {
        redis.delete(aliveKey(roomCode, seq, round));
        redis.delete(hpKey(roomCode, seq, round));
        redis.delete(eliminatedKey(roomCode, seq, round));
        // 공통 점수 저장(GameResult) Lua 스크립트가 "round 키 존재"로 라운드가 열렸는지 검증하므로,
        // 판(bout)이 열릴 때 bout 키(room:...:round:{r})에 마커를 남겨 그 검증을 통과시킨다.
        // (판별 alive/hp/eliminated/ex는 하위 키라 이 검증 대상이 아니다.)
        redis.opsForHash().put(boutKey(roomCode, seq, round), "round", String.valueOf(round));
        redis.opsForSet().add(aliveKey(roomCode, seq, round), tokens.toArray(String[]::new));
        String hp = hpKey(roomCode, seq, round);
        for (String token : tokens) {
            redis.opsForHash().put(hp, token, String.valueOf(initialHp));
        }
    }

    // --- 교환(exchange) 진행 ---

    public void openExchange(String roomCode, int seq, int round, int exchange, Long skillId, Instant startedAt) {
        String key = exchangeKey(roomCode, seq, round, exchange);
        redis.opsForHash().put(key, SKILL_ID_FIELD, String.valueOf(skillId));
        redis.opsForHash().put(key, STARTED_AT_FIELD, String.valueOf(startedAt.getEpochSecond()));
    }

    public Long getExchangeSkillId(String roomCode, int seq, int round, int exchange) {
        Object value = redis.opsForHash().get(exchangeKey(roomCode, seq, round, exchange), SKILL_ID_FIELD);
        return value == null ? null : Long.valueOf(value.toString());
    }

    public boolean exchangeExists(String roomCode, int seq, int round, int exchange) {
        return Boolean.TRUE.equals(redis.hasKey(exchangeKey(roomCode, seq, round, exchange)));
    }

    // 교환 종료(대상 지정 완료 vs 타임아웃) 경합 해소용 락. 먼저 도착한 호출만 true를 받는다.
    public boolean closeExchange(String roomCode, int seq, int round, int exchange, String reason) {
        boolean won = Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(exchangeKey(roomCode, seq, round, exchange), CLOSED_FIELD, reason)
        );
        log.info("[Repository] closeExchange(HSETNX) : round={} ex={} reason={} 결과={}",
            round, exchange, reason, won ? "성공" : "이미 닫힘");
        return won;
    }

    public boolean isExchangeClosed(String roomCode, int seq, int round, int exchange) {
        return redis.opsForHash().hasKey(exchangeKey(roomCode, seq, round, exchange), CLOSED_FIELD);
    }

    // --- 공격권/대상 선점 (동시 완성 예외 처리) ---

    public boolean claimAttacker(String roomCode, int seq, int round, int exchange, String token) {
        boolean won = Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(attackKey(roomCode, seq, round, exchange), ATTACKER_TOKEN_FIELD, token)
        );
        log.info("[Repository] claimAttacker(HSETNX) : round={} ex={} token={} 결과={}",
            round, exchange, token, won ? "선점 성공" : "선점 실패");
        return won;
    }

    public void recordAttackSkill(String roomCode, int seq, int round, int exchange, Long skillId, Instant judgedAt) {
        String key = attackKey(roomCode, seq, round, exchange);
        redis.opsForHash().put(key, SKILL_ID_FIELD, String.valueOf(skillId));
        redis.opsForHash().put(key, JUDGED_AT_FIELD, String.valueOf(judgedAt.getEpochSecond()));
    }

    public String getAttacker(String roomCode, int seq, int round, int exchange) {
        Object value = redis.opsForHash().get(attackKey(roomCode, seq, round, exchange), ATTACKER_TOKEN_FIELD);
        return value == null ? null : value.toString();
    }

    public boolean claimTarget(String roomCode, int seq, int round, int exchange, String token) {
        boolean won = Boolean.TRUE.equals(
            redis.opsForHash().putIfAbsent(attackKey(roomCode, seq, round, exchange), TARGET_TOKEN_FIELD, token)
        );
        log.info("[Repository] claimTarget(HSETNX) : round={} ex={} target={} 결과={}",
            round, exchange, token, won ? "선점 성공" : "이미 지정됨");
        return won;
    }

    // 대상 지정으로 교환이 판정난 결과(데미지/남은 HP/탈락 여부)를 attack 해시에 함께 남긴다 —
    // 인터미션 스냅샷(getAttack)이 "방금 무슨 공격이 들어갔는지"를 그대로 재현할 수 있게 하기 위함.
    public void recordAttackResolution(String roomCode, int seq, int round, int exchange, int damage, int hpAfter, boolean eliminated) {
        String key = attackKey(roomCode, seq, round, exchange);
        redis.opsForHash().put(key, DAMAGE_FIELD, String.valueOf(damage));
        redis.opsForHash().put(key, HP_AFTER_FIELD, String.valueOf(hpAfter));
        redis.opsForHash().put(key, ELIMINATED_FIELD, String.valueOf(eliminated));
    }

    // 해당 교환 attack 해시 전체. 필드가 하나도 없으면(공격이 없던 교환) 빈 맵.
    public Map<Object, Object> getAttack(String roomCode, int seq, int round, int exchange) {
        return redis.opsForHash().entries(attackKey(roomCode, seq, round, exchange));
    }

    // --- 판별 생존/HP ---

    public Set<String> getAlivePlayers(String roomCode, int seq, int round) {
        return redis.opsForSet().members(aliveKey(roomCode, seq, round));
    }

    public boolean isAlive(String roomCode, int seq, int round, String token) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(aliveKey(roomCode, seq, round), token));
    }

    public long decrementHp(String roomCode, int seq, int round, String token, int damage) {
        return redis.opsForHash().increment(hpKey(roomCode, seq, round), token, -damage);
    }

    public Map<Object, Object> getAllHp(String roomCode, int seq, int round) {
        return redis.opsForHash().entries(hpKey(roomCode, seq, round));
    }

    public void eliminate(String roomCode, int seq, int round, String token, Instant eliminatedAt) {
        redis.opsForSet().remove(aliveKey(roomCode, seq, round), token);
        redis.opsForZSet().add(eliminatedKey(roomCode, seq, round), token, eliminatedAt.toEpochMilli());
    }

    // 이 판에서 늦게 탈락한 순서(= 높은 순위)대로 반환.
    public List<String> getEliminatedOrderDesc(String roomCode, int seq, int round) {
        Set<String> members = redis.opsForZSet().reverseRange(eliminatedKey(roomCode, seq, round), 0, -1);
        return members == null ? List.of() : List.copyOf(members);
    }

    // --- 게임 종료 ---

    // 최종 순위(누적 점수순)는 WS(ninja:game-ended)로만 나가면 STOMP를 안 붙인 프론트는 못 받는다 —
    // GET .../state가 폴링으로도 읽을 수 있게 순서를 여기 별도로 저장한다(rank는 인덱스+1로 유도).
    public void saveRanking(String roomCode, int seq, List<String> orderedTokens) {
        String key = finalRankingKey(roomCode, seq);
        redis.delete(key);
        if (!orderedTokens.isEmpty()) {
            redis.opsForList().rightPushAll(key, orderedTokens);
        }
    }

    public List<String> getRanking(String roomCode, int seq) {
        List<String> values = redis.opsForList().range(finalRankingKey(roomCode, seq), 0, -1);
        return values == null ? List.of() : values;
    }
}
