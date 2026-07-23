package com.plaiground.domain.room.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.ConnectionRepository;
import com.plaiground.domain.room.repository.HeartbeatRefreshResult;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.LeaveRoomResult;
import com.plaiground.domain.room.repository.LeaveRoomStatus;
import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Duration;
import java.time.Instant;
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
class RedisHeartbeatGuardRepositoryIntegrationTests {

    private static final Instant BASE_TIME = Instant.parse("2026-07-22T00:00:00Z");

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
    private RoomRepository roomRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

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
    void rejectsHeartbeatWhenRoomDoesNotExist() {
        UUID participantId = UUID.randomUUID();

        HeartbeatRefreshResult result = connectionRepository.refreshHeartbeat(
            participantId,
            UUID.randomUUID(),
            Duration.ofSeconds(15)
        );

        assertThat(result).isEqualTo(HeartbeatRefreshResult.ROOM_NOT_FOUND);
        assertThat(connectionRepository.isAlive(participantId)).isFalse();
    }

    @Test
    void rejectsHeartbeatWhenParticipantDoesNotBelongToRoom() {
        Fixture fixture = createRoomWithHost();
        UUID participantId = UUID.randomUUID();

        HeartbeatRefreshResult result = connectionRepository.refreshHeartbeat(
            participantId,
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        assertThat(result)
            .isEqualTo(HeartbeatRefreshResult.PARTICIPANT_NOT_FOUND);
        assertThat(connectionRepository.isAlive(participantId)).isFalse();
    }

    @Test
    void refreshesHeartbeatAndRestoresConnectedStatus() {
        Fixture fixture = createRoomWithHost();
        participantRepository.updateConnectionStatus(
            fixture.room().roomId(),
            fixture.host().participantId(),
            ConnectionStatus.DISCONNECTED
        );

        HeartbeatRefreshResult result = connectionRepository.refreshHeartbeat(
            fixture.host().participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        assertThat(result).isEqualTo(HeartbeatRefreshResult.SUCCESS);
        assertThat(redisTemplate.opsForValue().get(
            RedisRoomKeys.heartbeat(fixture.host().participantId())
        )).isEqualTo(fixture.room().roomId().toString());
        assertThat(redisTemplate.getExpire(
            RedisRoomKeys.heartbeat(fixture.host().participantId())
        )).isBetween(1L, 15L);
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            fixture.host().participantId()
        ).orElseThrow().connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void rejectsHeartbeatTtlShorterThanOneMillisecond() {
        assertThatThrownBy(() -> connectionRepository.refreshHeartbeat(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Duration.ofNanos(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentTimeoutAndHeartbeatNeverLeaveOrphanHeartbeat() throws Exception {
        Fixture fixture = createRoomWithHost();
        Participant member = participant(
            UUID.randomUUID(),
            "경쟁 참가자",
            BASE_TIME.plusSeconds(1)
        );
        assertThat(participantRepository.tryAdd(fixture.room().roomId(), member))
            .isEqualTo(JoinParticipantResult.SUCCESS);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HeartbeatRefreshResult> heartbeat = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return connectionRepository.refreshHeartbeat(
                    member.participantId(),
                    fixture.room().roomId(),
                    Duration.ofSeconds(15)
                );
            });
            Future<LeaveRoomResult> timeout = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return participantRepository.leaveIfHeartbeatExpired(
                    fixture.room().roomId(),
                    member.participantId()
                );
            });

            workersReady.await();
            start.countDown();
            HeartbeatRefreshResult heartbeatResult = heartbeat.get();
            LeaveRoomResult timeoutResult = timeout.get();

            if (heartbeatResult == HeartbeatRefreshResult.SUCCESS) {
                assertThat(timeoutResult.status())
                    .isEqualTo(LeaveRoomStatus.HEARTBEAT_ACTIVE);
                assertThat(participantRepository.findById(
                    fixture.room().roomId(),
                    member.participantId()
                )).contains(member);
                assertThat(connectionRepository.isAlive(member.participantId()))
                    .isTrue();
            } else {
                assertThat(heartbeatResult)
                    .isEqualTo(HeartbeatRefreshResult.PARTICIPANT_NOT_FOUND);
                assertThat(timeoutResult.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
                assertThat(participantRepository.findById(
                    fixture.room().roomId(),
                    member.participantId()
                )).isEmpty();
                assertThat(connectionRepository.isAlive(member.participantId()))
                    .isFalse();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Fixture createRoomWithHost() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Room room = new Room(
            roomId,
            "AB23CD",
            hostId,
            4,
            RoomStatus.WAITING,
            1,
            BASE_TIME
        );
        Participant host = participant(hostId, "방장", BASE_TIME);
        assertThat(roomRepository.tryCreate(room, host)).isTrue();
        return new Fixture(room, host);
    }

    private static Participant participant(
        UUID participantId,
        String nickname,
        Instant joinedAt
    ) {
        return new Participant(
            participantId,
            nickname,
            false,
            ConnectionStatus.CONNECTED,
            joinedAt
        );
    }

    private record Fixture(Room room, Participant host) {
    }
}
