package com.camon.domain.game.ninja.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 닌자 Redis 원자 연산을 실제 Redis에 붙어 검증한다 (다른 도메인의 *RedisRepositoryIntegrationTest와
// 같은 패턴). 서비스 테스트는 이 저장소를 mock으로 대체하므로, 공격권 선점(HSETNX)·교환 닫기
// 경합·판 리셋 같은 "경합 버그가 가장 나기 쉬운" 연산은 여기서만 실제로 실행된다.
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class NinjaRedisRepositoryIntegrationTest {

    private static final String ROOM_CODE = "N1NJ4T";
    private static final int SEQ = 1;
    private static final int ROUND = 1;
    private static final int EXCHANGE = 1;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () ->
            System.getenv("REDIS_TEST_HOST")
        );
        registry.add("spring.data.redis.port", () ->
            Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"))
        );
    }

    @Autowired
    private NinjaRedisRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    // --- 공격권 선점: 동시 콤보 완성의 승자는 정확히 한 명 ---

    @Test
    void claimAttacker_underContention_grantsExactlyOneWinner() throws Exception {
        int players = 8;
        ExecutorService pool = Executors.newFixedThreadPool(players);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < players; i++) {
                String token = "player-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return repository.claimAttacker(ROOM_CODE, SEQ, ROUND, EXCHANGE, token);
                }));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
            // 선점자가 그대로 기록돼 있어야 한다 (덮어쓰기 없음)
            assertThat(repository.getAttacker(ROOM_CODE, SEQ, ROUND, EXCHANGE)).startsWith("player-");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimTarget_secondClaimIsRejected() {
        assertThat(repository.claimTarget(ROOM_CODE, SEQ, ROUND, EXCHANGE, "victim-1")).isTrue();
        assertThat(repository.claimTarget(ROOM_CODE, SEQ, ROUND, EXCHANGE, "victim-2")).isFalse();
        // 먼저 지정된 대상이 유지된다
        assertThat(repository.getAttack(ROOM_CODE, SEQ, ROUND, EXCHANGE))
            .containsEntry("target_token", "victim-1");
    }

    // --- 교환 닫기: 대상 지정 vs 타임아웃 경합은 먼저 닫은 쪽만 이긴다 ---

    @Test
    void closeExchange_firstCloserWins_regardlessOfReason() {
        assertThat(repository.closeExchange(ROOM_CODE, SEQ, ROUND, EXCHANGE, "TARGET")).isTrue();
        assertThat(repository.closeExchange(ROOM_CODE, SEQ, ROUND, EXCHANGE, "TIMEOUT")).isFalse();
        assertThat(repository.isExchangeClosed(ROOM_CODE, SEQ, ROUND, EXCHANGE)).isTrue();

        // 역방향(타임아웃이 먼저)도 동일하게 한 번만 닫힌다
        assertThat(repository.closeExchange(ROOM_CODE, SEQ, ROUND, 2, "TIMEOUT")).isTrue();
        assertThat(repository.closeExchange(ROOM_CODE, SEQ, ROUND, 2, "TARGET")).isFalse();
    }

    // --- HP 차감/탈락: 누적 차감과 늦게 탈락한 순서 기록 ---

    @Test
    void decrementHpAndEliminate_tracksHpAndEliminationOrder() {
        Set<String> tokens = Set.of("a", "b", "c");
        repository.startRound(ROOM_CODE, SEQ, ROUND, tokens, 100);

        assertThat(repository.decrementHp(ROOM_CODE, SEQ, ROUND, "b", 30)).isEqualTo(70);
        assertThat(repository.decrementHp(ROOM_CODE, SEQ, ROUND, "b", 45)).isEqualTo(25);
        assertThat(repository.decrementHp(ROOM_CODE, SEQ, ROUND, "b", 30)).isEqualTo(-5);

        Instant base = Instant.parse("2026-07-30T00:00:00Z");
        repository.eliminate(ROOM_CODE, SEQ, ROUND, "b", base);
        repository.eliminate(ROOM_CODE, SEQ, ROUND, "c", base.plusSeconds(10));

        assertThat(repository.isAlive(ROOM_CODE, SEQ, ROUND, "b")).isFalse();
        assertThat(repository.getAlivePlayers(ROOM_CODE, SEQ, ROUND)).containsExactly("a");
        // 늦게 탈락한 순서(높은 순위)대로 — c가 b보다 나중에 탈락했으니 앞에 온다
        assertThat(repository.getEliminatedOrderDesc(ROOM_CODE, SEQ, ROUND))
            .containsExactly("c", "b");
    }

    // --- 판 시작: 이전 판 상태 리셋 + 공통 점수 저장 계약(라운드 마커 키) ---

    @Test
    void startRound_resetsRoundStateAndLeavesCommonRoundMarker() {
        Set<String> tokens = Set.of("a", "b");
        repository.startRound(ROOM_CODE, SEQ, ROUND, tokens, 100);
        repository.decrementHp(ROOM_CODE, SEQ, ROUND, "a", 80);
        repository.eliminate(ROOM_CODE, SEQ, ROUND, "a", Instant.now());

        // 같은 판을 다시 시작하면(리셋) 전원 부활 + 풀피 + 탈락 기록 삭제
        repository.startRound(ROOM_CODE, SEQ, ROUND, tokens, 100);
        assertThat(repository.getAlivePlayers(ROOM_CODE, SEQ, ROUND)).containsExactlyInAnyOrder("a", "b");
        assertThat(repository.getAllHp(ROOM_CODE, SEQ, ROUND))
            .containsEntry("a", "100")
            .containsEntry("b", "100");
        assertThat(repository.getEliminatedOrderDesc(ROOM_CODE, SEQ, ROUND)).isEmpty();

        // 공통 점수 저장 Lua가 존재를 검증하는 room:{code}:session:{seq}:round:{r} 마커 —
        // 이 계약이 깨지면 점수 저장이 ROUND_NOT_FOUND로 터진다 (fetch에서 실제로 났던 버그).
        assertThat(redis.hasKey("room:%s:session:%d:round:%d".formatted(ROOM_CODE, SEQ, ROUND)))
            .isTrue();
    }

    // --- 스킬 셔플/드로우: 순서 소비와 순환, peek은 커서를 안 움직인다 ---

    @Test
    void drawNextSkill_consumesShuffledOrderAndWrapsAround() {
        repository.saveSkillOrder(ROOM_CODE, SEQ, List.of(11L, 22L, 33L));

        assertThat(repository.peekNextSkill(ROOM_CODE, SEQ)).isEqualTo(11L); // 커서 비이동
        assertThat(repository.drawNextSkill(ROOM_CODE, SEQ)).isEqualTo(11L);
        assertThat(repository.peekNextSkill(ROOM_CODE, SEQ)).isEqualTo(22L);
        assertThat(repository.drawNextSkill(ROOM_CODE, SEQ)).isEqualTo(22L);
        assertThat(repository.drawNextSkill(ROOM_CODE, SEQ)).isEqualTo(33L);
        // 풀 소진 후 순환 — 교환 수는 상한 전까지 제한이 없어 다시 앞에서부터
        assertThat(repository.drawNextSkill(ROOM_CODE, SEQ)).isEqualTo(11L);
    }

    @Test
    void drawNextSkill_returnsNullWhenOrderIsEmpty() {
        assertThat(repository.drawNextSkill(ROOM_CODE, SEQ)).isNull();
        assertThat(repository.peekNextSkill(ROOM_CODE, SEQ)).isNull();
    }

    // --- phase 전환: 인터미션 시각 필드의 설정/정리 ---

    @Test
    void phaseTransitions_manageIntermissionTimestamps() {
        Instant effectUntil = Instant.parse("2026-07-30T00:00:03Z");
        Instant nextRoundAt = Instant.parse("2026-07-30T00:00:06Z");

        repository.enterIntermission(ROOM_CODE, SEQ, effectUntil, nextRoundAt);
        assertThat(repository.getPhase(ROOM_CODE, SEQ)).isEqualTo(NinjaPhase.INTERMISSION);
        assertThat(repository.getEffectUntil(ROOM_CODE, SEQ)).isEqualTo(effectUntil);
        assertThat(repository.getNextRoundAt(ROOM_CODE, SEQ)).isEqualTo(nextRoundAt);

        // 타임아웃 인터미션(이펙트 없음) — null이면 필드가 지워져야 지난 값이 남지 않는다
        repository.enterIntermission(ROOM_CODE, SEQ, null, nextRoundAt);
        assertThat(repository.getEffectUntil(ROOM_CODE, SEQ)).isNull();

        // 다음 교환 진행으로 복귀 — 인터미션 시각이 전부 정리돼 재접속 스냅샷이 오염되지 않는다
        repository.enterRound(ROOM_CODE, SEQ);
        assertThat(repository.getPhase(ROOM_CODE, SEQ)).isEqualTo(NinjaPhase.ROUND);
        assertThat(repository.getNextRoundAt(ROOM_CODE, SEQ)).isNull();

        repository.enterEnded(ROOM_CODE, SEQ);
        assertThat(repository.getPhase(ROOM_CODE, SEQ)).isEqualTo(NinjaPhase.ENDED);
    }

    // --- 공격 기록 재현: 인터미션 스냅샷이 읽는 attack 해시 ---

    @Test
    void attackRecord_reconstructsResolutionSnapshot() {
        repository.claimAttacker(ROOM_CODE, SEQ, ROUND, EXCHANGE, "attacker");
        repository.recordAttackSkill(ROOM_CODE, SEQ, ROUND, EXCHANGE, 7L, Instant.parse("2026-07-30T00:00:01Z"));
        repository.claimTarget(ROOM_CODE, SEQ, ROUND, EXCHANGE, "victim");
        repository.recordAttackResolution(ROOM_CODE, SEQ, ROUND, EXCHANGE, 30, 70, false);

        assertThat(repository.getAttack(ROOM_CODE, SEQ, ROUND, EXCHANGE))
            .containsEntry("attacker_token", "attacker")
            .containsEntry("target_token", "victim")
            .containsEntry("skill_id", "7")
            .containsEntry("damage", "30")
            .containsEntry("hp_after", "70")
            .containsEntry("eliminated", "false");
        // 공격이 없던 교환은 빈 맵 (null 아님)
        assertThat(repository.getAttack(ROOM_CODE, SEQ, ROUND, 99)).isEmpty();
    }

    // --- 최종 순위 저장: 순서 보존 + 재저장 시 덮어쓰기 ---

    @Test
    void saveRanking_preservesOrderAndOverwrites() {
        repository.saveRanking(ROOM_CODE, SEQ, List.of("first", "second", "third"));
        assertThat(repository.getRanking(ROOM_CODE, SEQ)).containsExactly("first", "second", "third");

        repository.saveRanking(ROOM_CODE, SEQ, List.of("second", "first"));
        assertThat(repository.getRanking(ROOM_CODE, SEQ)).containsExactly("second", "first");

        assertThat(repository.getRanking(ROOM_CODE, 99)).isEmpty();
    }

    // --- 세션 청소: 접두사 하위 키까지 전부 삭제 ---

    @Test
    void clearSession_removesEverySessionKey() {
        repository.saveParticipants(ROOM_CODE, SEQ, Set.of("a", "b"));
        repository.saveSkillOrder(ROOM_CODE, SEQ, List.of(1L, 2L));
        repository.startRound(ROOM_CODE, SEQ, ROUND, Set.of("a", "b"), 100);
        repository.openExchange(ROOM_CODE, SEQ, ROUND, EXCHANGE, 1L, Instant.now());
        repository.claimAttacker(ROOM_CODE, SEQ, ROUND, EXCHANGE, "a");
        repository.setCurrentRound(ROOM_CODE, SEQ, ROUND);

        repository.clearSession(ROOM_CODE, SEQ);

        assertThat(redis.keys("room:%s:session:%d*".formatted(ROOM_CODE, SEQ))).isEmpty();
        assertThat(repository.getCurrentRound(ROOM_CODE, SEQ)).isNull();
        assertThat(repository.getAlivePlayers(ROOM_CODE, SEQ, ROUND)).isEmpty();
    }
}
