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
class RedisParticipantTimeoutRepositoryIntegrationTests {

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
    void keepsParticipantWhenHeartbeatIsActive() {
        Fixture fixture = createRoomWithHost();
        Participant member = participant(
            UUID.randomUUID(),
            "재접속 참가자",
            BASE_TIME.plusSeconds(1)
        );
        assertThat(participantRepository.tryAdd(fixture.room().roomId(), member))
            .isEqualTo(JoinParticipantResult.SUCCESS);
        connectionRepository.refreshHeartbeat(
            member.participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        LeaveRoomResult result = participantRepository.leaveIfHeartbeatExpired(
            fixture.room().roomId(),
            member.participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.HEARTBEAT_ACTIVE);
        assertThat(result.roomDeleted()).isFalse();
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            member.participantId()
        )).contains(member);
        assertThat(connectionRepository.isAlive(member.participantId())).isTrue();
    }

    @Test
    void removesParticipantWhenHeartbeatIsExpired() {
        Fixture fixture = createRoomWithHost();
        Participant member = participant(
            UUID.randomUUID(),
            "타임아웃 참가자",
            BASE_TIME.plusSeconds(1)
        );
        participantRepository.tryAdd(fixture.room().roomId(), member);

        LeaveRoomResult result = participantRepository.leaveIfHeartbeatExpired(
            fixture.room().roomId(),
            member.participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.newHostParticipantId())
            .isEqualTo(fixture.host().participantId());
        assertThat(result.roomDeleted()).isFalse();
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            member.participantId()
        )).isEmpty();
    }

    @Test
    void transfersTimedOutHostToEarliestAliveParticipant() {
        Fixture fixture = createRoomWithHost();
        Participant expiredMember = participant(
            UUID.randomUUID(),
            "먼저 입장했지만 끊긴 참가자",
            BASE_TIME.plusSeconds(1)
        );
        Participant aliveMember = participant(
            UUID.randomUUID(),
            "연결된 참가자",
            BASE_TIME.plusSeconds(2)
        );
        participantRepository.tryAdd(fixture.room().roomId(), expiredMember);
        participantRepository.tryAdd(fixture.room().roomId(), aliveMember);
        connectionRepository.refreshHeartbeat(
            aliveMember.participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        LeaveRoomResult result = participantRepository.leaveIfHeartbeatExpired(
            fixture.room().roomId(),
            fixture.host().participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.newHostParticipantId())
            .isEqualTo(aliveMember.participantId());
        assertThat(result.hostChanged()).isTrue();
        assertThat(roomRepository.findById(fixture.room().roomId()).orElseThrow()
            .hostParticipantId()).isEqualTo(aliveMember.participantId());
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            expiredMember.participantId()
        )).contains(expiredMember);
    }

    @Test
    void deletesRoomAndCodeWhenLastParticipantTimesOut() {
        Fixture fixture = createRoomWithHost();

        LeaveRoomResult result = participantRepository.leaveIfHeartbeatExpired(
            fixture.room().roomId(),
            fixture.host().participantId()
        );

        assertThat(result.status()).isEqualTo(LeaveRoomStatus.SUCCESS);
        assertThat(result.roomDeleted()).isTrue();
        assertThat(roomRepository.findById(fixture.room().roomId())).isEmpty();
        assertThat(roomRepository.findByCode(fixture.room().roomCode())).isEmpty();
    }

    private Fixture createRoomWithHost() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Room room = new Room(
            roomId,
            "AB23CD",
            "테스트 방",
            hostId,
            4,
            RoomStatus.WAITING,
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
