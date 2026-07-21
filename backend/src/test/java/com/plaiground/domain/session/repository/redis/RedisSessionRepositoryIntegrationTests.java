package com.plaiground.domain.session.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.repository.SessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisSessionRepositoryIntegrationTests {

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
    private SessionRepository sessionRepository;

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
    void savesFindsUpdatesAndDeletesGuestSession() {
        UUID participantId = UUID.randomUUID();
        GuestSession created = new GuestSession(
            participantId,
            "첫 닉네임",
            Instant.parse("2026-07-21T00:00:00Z")
        );

        Instant expiresAt = Instant.now().plusSeconds(43_200);
        sessionRepository.save(created, expiresAt);
        assertThat(sessionRepository.findByParticipantId(participantId))
            .contains(created);
        assertThat(redisTemplate.getExpire(
            RedisGuestSessionKeys.session(participantId)
        )).isBetween(1L, 43_200L);

        GuestSession updated = new GuestSession(
            participantId,
            "바뀐 닉네임",
            Instant.parse("2026-07-21T01:00:00Z")
        );
        sessionRepository.save(updated, expiresAt);
        assertThat(sessionRepository.findByParticipantId(participantId))
            .contains(updated);

        sessionRepository.delete(participantId);
        assertThat(sessionRepository.findByParticipantId(participantId))
            .isEmpty();
    }

    @Test
    void returnsEmptyForUnknownParticipant() {
        assertThat(sessionRepository.findByParticipantId(UUID.randomUUID()))
            .isEmpty();
    }
}
