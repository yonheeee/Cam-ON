package com.plaiground.domain.room.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.ConnectionRepository;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.LeaveRoomResult;
import com.plaiground.domain.room.repository.LeaveRoomStatus;
import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisRoomLifecycleRepositoryIntegrationTests {

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
    void leavesWithoutChangingHostWhenParticipantIsNotHost() {
        Fixture fixture = createRoomWithHost();
        Participant member = participant(
            UUID.randomUUID(),
            "참가자",
            BASE_TIME.plusSeconds(1)
        );
        assertThat(participantRepository.tryAdd(fixture.room().roomId(), member))
            .isEqualTo(JoinParticipantResult.SUCCESS);
        connectionRepository.refreshHeartbeat(
            member.participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        LeaveRoomResult result = participantRepository.leave(
            fixture.room().roomId(),
            member.participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.previousHostParticipantId())
            .isEqualTo(fixture.host().participantId());
        assertThat(result.newHostParticipantId())
            .isEqualTo(fixture.host().participantId());
        assertThat(result.hostChanged()).isFalse();
        assertThat(result.roomDeleted()).isFalse();
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            member.participantId()
        )).isEmpty();
        assertThat(connectionRepository.isAlive(member.participantId())).isFalse();
        assertThat(participantRepository.tryAdd(
            fixture.room().roomId(),
            participant(UUID.randomUUID(), "참가자", BASE_TIME.plusSeconds(2))
        )).isEqualTo(JoinParticipantResult.SUCCESS);
    }

    @Test
    void transfersHostToEarliestRemainingParticipant() {
        Fixture fixture = createRoomWithHost();
        Participant first = participant(
            UUID.randomUUID(),
            "첫 번째",
            BASE_TIME.plusSeconds(1)
        );
        Participant second = participant(
            UUID.randomUUID(),
            "두 번째",
            BASE_TIME.plusSeconds(2)
        );
        participantRepository.tryAdd(fixture.room().roomId(), first);
        participantRepository.tryAdd(fixture.room().roomId(), second);

        LeaveRoomResult result = participantRepository.leave(
            fixture.room().roomId(),
            fixture.host().participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.previousHostParticipantId())
            .isEqualTo(fixture.host().participantId());
        assertThat(result.newHostParticipantId()).isEqualTo(first.participantId());
        assertThat(result.hostChanged()).isTrue();
        assertThat(result.roomDeleted()).isFalse();
        assertThat(roomRepository.findById(fixture.room().roomId()).orElseThrow()
            .hostParticipantId()).isEqualTo(first.participantId());
    }

    @Test
    void deletesRoomWhenLastParticipantLeaves() {
        Fixture fixture = createRoomWithHost();
        connectionRepository.refreshHeartbeat(
            fixture.host().participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        LeaveRoomResult result = participantRepository.leave(
            fixture.room().roomId(),
            fixture.host().participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.roomDeleted()).isTrue();
        assertThat(result.newHostParticipantId()).isNull();
        assertThat(roomRepository.findById(fixture.room().roomId())).isEmpty();
        assertThat(participantRepository.findAll(fixture.room().roomId())).isEmpty();
        assertThat(connectionRepository.isAlive(fixture.host().participantId()))
            .isFalse();
    }

    @Test
    void reportsMissingRoomAndParticipant() {
        UUID unknownRoomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        assertThat(participantRepository.leave(unknownRoomId, participantId).status())
            .isEqualTo(LeaveRoomStatus.ROOM_NOT_FOUND);

        Fixture fixture = createRoomWithHost();
        assertThat(participantRepository.leave(
            fixture.room().roomId(),
            participantId
        ).status()).isEqualTo(LeaveRoomStatus.PARTICIPANT_NOT_FOUND);
    }

    @Test
    void concurrentLeavesEndWithDeletedRoom() throws Exception {
        Fixture fixture = createRoomWithHost();
        Participant member = participant(
            UUID.randomUUID(),
            "참가자",
            BASE_TIME.plusSeconds(1)
        );
        participantRepository.tryAdd(fixture.room().roomId(), member);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<LeaveRoomResult> hostLeave = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return participantRepository.leave(
                    fixture.room().roomId(),
                    fixture.host().participantId()
                );
            });
            Future<LeaveRoomResult> memberLeave = executor.submit(() -> {
                workersReady.countDown();
                start.await();
                return participantRepository.leave(
                    fixture.room().roomId(),
                    member.participantId()
                );
            });

            workersReady.await();
            start.countDown();
            List<LeaveRoomResult> results = List.of(
                hostLeave.get(),
                memberLeave.get()
            );

            assertThat(results)
                .extracting(LeaveRoomResult::status)
                .containsOnly(LeaveRoomStatus.SUCCESS);
            assertThat(results)
                .filteredOn(LeaveRoomResult::roomDeleted)
                .hasSize(1);
            assertThat(roomRepository.findById(fixture.room().roomId())).isEmpty();
            assertThat(participantRepository.findAll(fixture.room().roomId()))
                .isEmpty();
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
            BASE_TIME
        );
        Participant host = participant(hostId, "방장", BASE_TIME);
        roomRepository.saveIfAbsent(room);
        assertThat(participantRepository.tryAdd(roomId, host))
            .isEqualTo(JoinParticipantResult.SUCCESS);
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
