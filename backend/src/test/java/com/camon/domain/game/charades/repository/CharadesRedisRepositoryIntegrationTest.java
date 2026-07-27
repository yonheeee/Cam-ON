package com.camon.domain.game.charades.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.camon.domain.game.charades.domain.CharadesGameState;
import com.camon.domain.game.charades.domain.CharadesTurnStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class CharadesRedisRepositoryIntegrationTest {

    private static final String ROOM_CODE = "CH4R4D";
    private static final int SESSION_SEQ = 1;

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
    private CharadesRedisRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void initializesAndReadsPresenterOrder() {
        List<UUID> presenterOrder = List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );

        repository.initialize(ROOM_CODE, SESSION_SEQ, 5, 7L, presenterOrder);

        CharadesGameState state = repository.findState(ROOM_CODE, SESSION_SEQ)
            .orElseThrow();
        assertThat(state.currentRound()).isZero();
        assertThat(state.totalRounds()).isEqualTo(5);
        assertThat(state.currentTurn()).isZero();
        assertThat(state.totalTurnsInRound()).isEqualTo(3);
        assertThat(state.topicId()).isEqualTo(7L);
        assertThat(state.presenterId()).isNull();
        assertThat(state.missionId()).isNull();
        assertThat(state.expiresAt()).isNull();
        assertThat(state.status()).isEqualTo(CharadesTurnStatus.READY);
        assertThat(repository.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .containsExactlyElementsOf(presenterOrder);
        assertThat(repository.getUsedMissionIds(ROOM_CODE, SESSION_SEQ)).isEmpty();
    }

    @Test
    void opensRoundAndRecordsMissionWithoutStoringWord() {
        UUID presenterId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-07-27T12:02:00.123Z");
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, UUID.randomUUID(), UUID.randomUUID())
        );

        assertThat(repository.openTurn(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            1,
            presenterId,
            42L,
            expiresAt
        )).isTrue();

        CharadesGameState state = repository.findState(ROOM_CODE, SESSION_SEQ)
            .orElseThrow();
        assertThat(state.currentRound()).isEqualTo(1);
        assertThat(state.currentTurn()).isEqualTo(1);
        assertThat(state.totalTurnsInRound()).isEqualTo(3);
        assertThat(state.topicId()).isEqualTo(7L);
        assertThat(state.presenterId()).isEqualTo(presenterId);
        assertThat(state.missionId()).isEqualTo(42L);
        assertThat(state.expiresAt()).isEqualTo(expiresAt);
        assertThat(state.status()).isEqualTo(CharadesTurnStatus.PLAYING);
        assertThat(repository.getUsedMissionIds(ROOM_CODE, SESSION_SEQ))
            .containsExactly(42L);
        assertThat(redis.opsForHash().values(
            CharadesRedisKeys.state(ROOM_CODE, SESSION_SEQ)
        )).doesNotContain("정답 원문");
    }

    @Test
    void tracksEveryUsedMissionOnlyOnce() {
        UUID presenterId = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, UUID.randomUUID(), UUID.randomUUID())
        );

        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, presenterId, 11L, Instant.now()
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 2, presenterId, 12L, Instant.now()
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 2, 1, presenterId, 11L, Instant.now()
        );

        assertThat(repository.getUsedMissionIds(ROOM_CODE, SESSION_SEQ))
            .isEqualTo(Set.of(11L, 12L));
    }

    @Test
    void transitionsRoundStatusOnlyFromExpectedStatus() {
        UUID presenterId = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, UUID.randomUUID(), UUID.randomUUID())
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, presenterId, 1L, Instant.now()
        );

        assertThat(repository.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.CORRECT
        )).isTrue();
        assertThat(repository.transitionStatus(
            ROOM_CODE,
            SESSION_SEQ,
            CharadesTurnStatus.PLAYING,
            CharadesTurnStatus.TIMEOUT
        )).isFalse();
        assertThat(repository.findState(ROOM_CODE, SESSION_SEQ).orElseThrow().status())
            .isEqualTo(CharadesTurnStatus.CORRECT);
    }

    @Test
    void acceptsOnlyFirstCorrectAnswerAndRecordsWinner() {
        UUID presenterId = UUID.randomUUID();
        UUID firstAnswererId = UUID.randomUUID();
        UUID secondAnswererId = UUID.randomUUID();
        Instant answeredAt = Instant.parse("2026-07-27T12:01:30.123Z");
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, firstAnswererId, secondAnswererId)
        );
        repository.openTurn(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            1,
            presenterId,
            1L,
            answeredAt.plusSeconds(30)
        );

        assertThat(repository.claimCorrectAnswer(
            ROOM_CODE,
            SESSION_SEQ,
            firstAnswererId,
            answeredAt
        )).isTrue();
        assertThat(repository.claimCorrectAnswer(
            ROOM_CODE,
            SESSION_SEQ,
            secondAnswererId,
            answeredAt.plusMillis(1)
        )).isFalse();
        assertThat(repository.getCorrectParticipantId(ROOM_CODE, SESSION_SEQ))
            .isEqualTo(firstAnswererId);
        assertThat(repository.getAnsweredAt(ROOM_CODE, SESSION_SEQ))
            .isEqualTo(answeredAt);
        assertThat(repository.findState(ROOM_CODE, SESSION_SEQ).orElseThrow().status())
            .isEqualTo(CharadesTurnStatus.CORRECT);
        assertThat(repository.getRoundScores(ROOM_CODE, SESSION_SEQ, 1))
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                presenterId, 1L,
                firstAnswererId, 1L
            ));
    }

    @Test
    void openingNextTurnClearsPreviousAnswerWinner() {
        UUID presenterId = UUID.randomUUID();
        UUID answererId = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, answererId, UUID.randomUUID())
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, presenterId, 1L, Instant.now()
        );
        repository.claimCorrectAnswer(
            ROOM_CODE, SESSION_SEQ, answererId, Instant.now()
        );

        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 2, answererId, 2L, Instant.now()
        );

        assertThat(repository.getCorrectParticipantId(ROOM_CODE, SESSION_SEQ))
            .isNull();
        assertThat(repository.getAnsweredAt(ROOM_CODE, SESSION_SEQ)).isNull();
    }

    @Test
    void concurrentStatusTransitionsHaveExactlyOneWinner() throws Exception {
        UUID presenterId = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, UUID.randomUUID(), UUID.randomUUID())
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, presenterId, 1L, Instant.now()
        );
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                CharadesTurnStatus target = index % 2 == 0
                    ? CharadesTurnStatus.CORRECT
                    : CharadesTurnStatus.TIMEOUT;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.transitionStatus(
                        ROOM_CODE,
                        SESSION_SEQ,
                        CharadesTurnStatus.PLAYING,
                        target
                    );
                }));
            }
            ready.await();
            start.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            assertThat(results).filteredOn(Boolean.TRUE::equals).hasSize(1);
            assertThat(repository.findState(ROOM_CODE, SESSION_SEQ).orElseThrow().status())
                .isIn(CharadesTurnStatus.CORRECT, CharadesTurnStatus.TIMEOUT);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reinitializeReplacesPreviousCharadesState() {
        UUID oldPresenter = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(oldPresenter, UUID.randomUUID(), UUID.randomUUID())
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, oldPresenter, 99L, Instant.now()
        );
        List<UUID> newOrder = List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );

        repository.initialize(ROOM_CODE, SESSION_SEQ, 7, 8L, newOrder);

        CharadesGameState state = repository.findState(ROOM_CODE, SESSION_SEQ)
            .orElseThrow();
        assertThat(state.currentRound()).isZero();
        assertThat(state.totalRounds()).isEqualTo(7);
        assertThat(state.currentTurn()).isZero();
        assertThat(state.totalTurnsInRound()).isEqualTo(3);
        assertThat(state.topicId()).isEqualTo(8L);
        assertThat(state.status()).isEqualTo(CharadesTurnStatus.READY);
        assertThat(repository.getPresenterOrder(ROOM_CODE, SESSION_SEQ))
            .containsExactlyElementsOf(newOrder);
        assertThat(repository.getUsedMissionIds(ROOM_CODE, SESSION_SEQ)).isEmpty();
    }

    @Test
    void clearsOnlyCharadesKeys() {
        UUID presenterId = UUID.randomUUID();
        repository.initialize(
            ROOM_CODE,
            SESSION_SEQ,
            3,
            7L,
            List.of(presenterId, UUID.randomUUID(), UUID.randomUUID())
        );
        repository.openTurn(
            ROOM_CODE, SESSION_SEQ, 1, 1, presenterId, 1L, Instant.now()
        );
        String sessionKey = "room:%s:session:%d".formatted(ROOM_CODE, SESSION_SEQ);
        redis.opsForHash().put(sessionKey, "game_id", "3");

        repository.clear(ROOM_CODE, SESSION_SEQ);

        assertThat(repository.findState(ROOM_CODE, SESSION_SEQ)).isEmpty();
        assertThat(repository.getPresenterOrder(ROOM_CODE, SESSION_SEQ)).isEmpty();
        assertThat(repository.getUsedMissionIds(ROOM_CODE, SESSION_SEQ)).isEmpty();
        assertThat(repository.getRoundScores(ROOM_CODE, SESSION_SEQ, 1)).isEmpty();
        assertThat(redis.opsForHash().get(sessionKey, "game_id")).isEqualTo("3");
    }

    @Test
    void validatesRoundCountAndPresenterOrder() {
        UUID participantId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.initialize(
            ROOM_CODE, SESSION_SEQ, 2, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one of 3, 5, 7, 9");
        assertThatThrownBy(() -> repository.initialize(
            ROOM_CODE, SESSION_SEQ, 4, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one of 3, 5, 7, 9");
        assertThatThrownBy(() -> repository.initialize(
            ROOM_CODE, SESSION_SEQ, 3, 7L, List.of(participantId, participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicates");
    }

    @Test
    void cannotOpenRoundBeforeInitialization() {
        assertThat(repository.openTurn(
            ROOM_CODE,
            SESSION_SEQ,
            1,
            1,
            UUID.randomUUID(),
            1L,
            Instant.now()
        )).isFalse();
    }
}
