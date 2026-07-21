package com.plaiground.domain.room.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.ConnectionRepository;
import com.plaiground.domain.room.repository.JoinParticipantResult;
import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisRoomRepositoryIntegrationTests {

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
    void savesReadsUpdatesAndDeletesRoom() {
        Room room = room(2);

        assertThat(roomRepository.saveIfAbsent(room)).isTrue();
        assertThat(roomRepository.saveIfAbsent(room)).isFalse();
        assertThat(roomRepository.findById(room.roomId())).contains(room);

        UUID newHostId = UUID.randomUUID();
        roomRepository.updateHost(room.roomId(), newHostId);
        roomRepository.updateStatus(room.roomId(), RoomStatus.PLAYING);

        Room updated = roomRepository.findById(room.roomId()).orElseThrow();
        assertThat(updated.hostParticipantId()).isEqualTo(newHostId);
        assertThat(updated.status()).isEqualTo(RoomStatus.PLAYING);

        roomRepository.updateStatus(room.roomId(), RoomStatus.WAITING);
        Participant participant = participant("삭제 대상");
        participantRepository.tryAdd(room.roomId(), participant);
        connectionRepository.refreshHeartbeat(
            participant.participantId(),
            room.roomId(),
            Duration.ofSeconds(15)
        );

        roomRepository.delete(room.roomId());
        assertThat(roomRepository.findById(room.roomId())).isEmpty();
        assertThat(participantRepository.findAll(room.roomId())).isEmpty();
        assertThat(connectionRepository.isAlive(participant.participantId()))
            .isFalse();
    }

    @Test
    void atomicallyValidatesParticipantJoinConditions() {
        assertThat(participantRepository.tryAdd(
            UUID.randomUUID(),
            participant("없는 방")
        )).isEqualTo(JoinParticipantResult.ROOM_NOT_FOUND);

        Room room = room(2);
        roomRepository.saveIfAbsent(room);

        Participant first = participant("싸피");
        assertThat(participantRepository.tryAdd(room.roomId(), first))
            .isEqualTo(JoinParticipantResult.SUCCESS);
        assertThat(participantRepository.tryAdd(room.roomId(), first))
            .isEqualTo(JoinParticipantResult.ALREADY_JOINED);
        assertThat(participantRepository.tryAdd(
            room.roomId(),
            participant("싸피")
        )).isEqualTo(JoinParticipantResult.NICKNAME_DUPLICATED);

        Participant second = participant("플레이");
        assertThat(participantRepository.tryAdd(room.roomId(), second))
            .isEqualTo(JoinParticipantResult.SUCCESS);
        assertThat(participantRepository.tryAdd(
            room.roomId(),
            participant("세 번째")
        )).isEqualTo(JoinParticipantResult.ROOM_FULL);

        roomRepository.updateStatus(room.roomId(), RoomStatus.PLAYING);
        assertThat(participantRepository.tryAdd(
            room.roomId(),
            participant("게임 중 참가")
        )).isEqualTo(JoinParticipantResult.ROOM_ALREADY_STARTED);

        assertThat(participantRepository.findAll(room.roomId()))
            .containsExactly(first, second);
    }

    @Test
    void concurrentJoinsNeverExceedRoomCapacity() throws Exception {
        Room room = room(2);
        roomRepository.saveIfAbsent(room);

        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch workersReady = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JoinParticipantResult>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                int participantNumber = index;
                futures.add(executor.submit(() -> {
                    workersReady.countDown();
                    start.await();
                    return participantRepository.tryAdd(
                        room.roomId(),
                        new Participant(
                            UUID.randomUUID(),
                            "동시참가-" + participantNumber,
                            false,
                            ConnectionStatus.CONNECTED,
                            Instant.parse("2026-07-21T00:00:00Z")
                                .plusSeconds(participantNumber)
                        )
                    );
                }));
            }

            workersReady.await();
            start.countDown();

            List<JoinParticipantResult> results = new ArrayList<>();
            for (Future<JoinParticipantResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results)
                .filteredOn(JoinParticipantResult.SUCCESS::equals)
                .hasSize(2);
            assertThat(results)
                .filteredOn(JoinParticipantResult.ROOM_FULL::equals)
                .hasSize(attempts - 2);
            assertThat(participantRepository.findAll(room.roomId())).hasSize(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void updatesAndRemovesParticipant() {
        Room room = room(4);
        roomRepository.saveIfAbsent(room);
        Participant participant = participant("게스트");
        participantRepository.tryAdd(room.roomId(), participant);

        participantRepository.updateReady(
            room.roomId(),
            participant.participantId(),
            true
        );
        participantRepository.updateConnectionStatus(
            room.roomId(),
            participant.participantId(),
            ConnectionStatus.DISCONNECTED
        );

        Participant updated = participantRepository.findById(
            room.roomId(),
            participant.participantId()
        ).orElseThrow();
        assertThat(updated.ready()).isTrue();
        assertThat(updated.connectionStatus())
            .isEqualTo(ConnectionStatus.DISCONNECTED);

        participantRepository.resetAllReady(room.roomId());
        assertThat(participantRepository.findById(
            room.roomId(),
            participant.participantId()
        ).orElseThrow().ready()).isFalse();

        participantRepository.remove(room.roomId(), participant.participantId());
        assertThat(participantRepository.findAll(room.roomId())).isEmpty();
        assertThat(participantRepository.tryAdd(
            room.roomId(),
            participant("게스트")
        )).isEqualTo(JoinParticipantResult.SUCCESS);
    }

    @Test
    void refreshesAndRemovesHeartbeat() {
        UUID participantId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();

        connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        );
        assertThat(connectionRepository.isAlive(participantId)).isTrue();
        assertThat(redisTemplate.getExpire(
            RedisRoomKeys.heartbeat(participantId)
        )).isBetween(1L, 15L);

        connectionRepository.removeHeartbeat(participantId);
        assertThat(connectionRepository.isAlive(participantId)).isFalse();
    }

    private static Room room(int maxPlayers) {
        UUID hostId = UUID.randomUUID();
        return new Room(
            UUID.randomUUID(),
            "테스트 방",
            hostId,
            maxPlayers,
            RoomStatus.WAITING,
            Instant.parse("2026-07-21T00:00:00Z")
        );
    }

    private static Participant participant(String nickname) {
        return new Participant(
            UUID.randomUUID(),
            nickname,
            false,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
    }
}
