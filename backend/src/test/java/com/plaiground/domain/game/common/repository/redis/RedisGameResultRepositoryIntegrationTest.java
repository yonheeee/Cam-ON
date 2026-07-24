package com.plaiground.domain.game.common.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaiground.domain.game.common.repository.GameResultRepository;
import com.plaiground.domain.game.common.repository.SaveRoundResult;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class RedisGameResultRepositoryIntegrationTest {

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
    private GameResultRepository gameResultRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void savesRoundResultsAndAccumulatesSessionAndCourseTotals() {
        Room room = saveRoom();
        createSessionAndRound(room, 1, 1);
        createRound(room, 1, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            1,
            Map.of(first, 5L, second, 4L)
        )).isEqualTo(SaveRoundResult.SUCCESS);
        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            2,
            Map.of(first, 3L, second, 5L)
        )).isEqualTo(SaveRoundResult.SUCCESS);

        assertThat(gameResultRepository.findRoundResults(room.roomId(), 1, 1))
            .containsExactlyInAnyOrderEntriesOf(Map.of(first, 5L, second, 4L));
        assertThat(gameResultRepository.findSessionTotals(room.roomId(), 1))
            .containsExactlyInAnyOrderEntriesOf(Map.of(first, 8L, second, 9L));
        assertThat(gameResultRepository.findCourseTotals(room.roomId()))
            .containsExactlyInAnyOrderEntriesOf(Map.of(first, 8L, second, 9L));
    }

    @Test
    void rejectsDuplicateRoundWithoutAccumulatingScoresAgain() {
        Room room = saveRoom();
        createSessionAndRound(room, 1, 1);
        UUID participantId = UUID.randomUUID();

        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            1,
            Map.of(participantId, 5L)
        )).isEqualTo(SaveRoundResult.SUCCESS);
        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            1,
            Map.of(participantId, 100L)
        )).isEqualTo(SaveRoundResult.ALREADY_SAVED);

        assertThat(gameResultRepository.findRoundResults(room.roomId(), 1, 1))
            .containsExactlyEntriesOf(Map.of(participantId, 5L));
        assertThat(gameResultRepository.findSessionTotals(room.roomId(), 1))
            .containsExactlyEntriesOf(Map.of(participantId, 5L));
        assertThat(gameResultRepository.findCourseTotals(room.roomId()))
            .containsExactlyEntriesOf(Map.of(participantId, 5L));
    }

    @Test
    void reportsMissingRoomSessionAndRound() {
        UUID participantId = UUID.randomUUID();
        assertThat(gameResultRepository.saveRoundResults(
            UUID.randomUUID(),
            1,
            1,
            Map.of(participantId, 5L)
        )).isEqualTo(SaveRoundResult.ROOM_NOT_FOUND);

        Room room = saveRoom();
        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            1,
            Map.of(participantId, 5L)
        )).isEqualTo(SaveRoundResult.SESSION_NOT_FOUND);

        createSession(room, 1);
        assertThat(gameResultRepository.saveRoundResults(
            room.roomId(),
            1,
            1,
            Map.of(participantId, 5L)
        )).isEqualTo(SaveRoundResult.ROUND_NOT_FOUND);
    }

    @Test
    void concurrentDuplicateSavesAccumulateExactlyOnce() throws Exception {
        Room room = saveRoom();
        createSessionAndRound(room, 1, 1);
        UUID participantId = UUID.randomUUID();
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SaveRoundResult>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return gameResultRepository.saveRoundResults(
                        room.roomId(),
                        1,
                        1,
                        Map.of(participantId, 5L)
                    );
                }));
            }
            ready.await();
            start.countDown();

            List<SaveRoundResult> results = new ArrayList<>();
            for (Future<SaveRoundResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(SaveRoundResult.SUCCESS::equals)
                .hasSize(1);
            assertThat(results).filteredOn(SaveRoundResult.ALREADY_SAVED::equals)
                .hasSize(attempts - 1);
            assertThat(gameResultRepository.findSessionTotals(room.roomId(), 1))
                .containsExactlyEntriesOf(Map.of(participantId, 5L));
            assertThat(gameResultRepository.findCourseTotals(room.roomId()))
                .containsExactlyEntriesOf(Map.of(participantId, 5L));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Room saveRoom() {
        Room room = new Room(
            UUID.randomUUID(),
            "AB23CD",
            UUID.randomUUID(),
            4,
            RoomStatus.PLAYING,
            1,
            Instant.parse("2026-07-24T00:00:00Z")
        );
        roomRepository.saveIfAbsent(room);
        return room;
    }

    private void createSessionAndRound(Room room, int sessionSeq, int round) {
        createSession(room, sessionSeq);
        createRound(room, sessionSeq, round);
    }

    private void createSession(Room room, int sessionSeq) {
        redisTemplate.opsForHash().put(
            RedisGameResultKeys.session(room.roomCode(), sessionSeq),
            "game_id",
            "1"
        );
    }

    private void createRound(Room room, int sessionSeq, int round) {
        redisTemplate.opsForHash().put(
            RedisGameResultKeys.round(room.roomCode(), sessionSeq, round),
            "round",
            Integer.toString(round)
        );
    }
}
