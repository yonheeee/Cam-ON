package com.camon.domain.game.fetch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class FetchObjectRedisRepositoryIntegrationTest {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.data.redis.host",
            () -> System.getenv("REDIS_TEST_HOST")
        );
        registry.add(
            "spring.data.redis.port",
            () -> Integer.parseInt(
                System.getenv().getOrDefault("REDIS_TEST_PORT", "6379")
            )
        );
    }

    @Autowired
    private FetchObjectRedisRepository repository;

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
    void preservesCommonSessionAndClosesRoundOnlyOnce() {
        String roomCode = "AB12CD";
        int sessionSeq = 1;
        String sessionKey = FetchObjectRedisKeys.session(roomCode, sessionSeq);
        redis.opsForHash().put(sessionKey, "course_marker", "keep");
        UUID participantId = UUID.randomUUID();

        repository.initialize(
            roomCode,
            sessionSeq,
            2L,
            2,
            List.of(participantId),
            List.of(10L, 11L)
        );
        assertThat(redis.opsForHash().get(sessionKey, "course_marker"))
            .isEqualTo("keep");
        assertThat(repository.getMissionIdAt(roomCode, sessionSeq, 1))
            .isEqualTo(10L);
        assertThat(repository.getParticipants(roomCode, sessionSeq))
            .containsExactly(participantId);

        Instant startedAt = Instant.parse("2026-07-29T00:00:00Z");
        repository.openRound(
            roomCode,
            sessionSeq,
            1,
            10L,
            "휴대폰",
            startedAt,
            startedAt.plusSeconds(3),
            startedAt.plusSeconds(23)
        );

        assertThat(repository.closeRoundIfPlaying(
            roomCode,
            sessionSeq,
            1,
            startedAt.plusSeconds(23),
            startedAt.plusSeconds(23)
        )).isTrue();
        assertThat(repository.closeRoundIfPlaying(
            roomCode,
            sessionSeq,
            1,
            startedAt.plusSeconds(23),
            startedAt.plusSeconds(24)
        )).isFalse();
    }
}
