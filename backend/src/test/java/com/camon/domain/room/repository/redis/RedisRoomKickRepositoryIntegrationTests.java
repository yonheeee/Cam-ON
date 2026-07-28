package com.camon.domain.room.repository.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.ConnectionRepository;
import com.camon.domain.room.repository.JoinParticipantResult;
import com.camon.domain.room.repository.KickParticipantResult;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
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
class RedisRoomKickRepositoryIntegrationTests {

    private static final Instant BASE_TIME = Instant.parse("2026-07-28T00:00:00Z");

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
    void kickRemovesParticipantAndBlocksRejoin() {
        Fixture fixture = createRoomWithHostAndMember();
        connectionRepository.refreshHeartbeat(
            fixture.member().participantId(),
            fixture.room().roomId(),
            Duration.ofSeconds(15)
        );

        KickParticipantResult result = participantRepository.kick(
            fixture.room().roomId(),
            fixture.host().participantId(),
            fixture.member().participantId()
        );

        assertThat(result).isEqualTo(KickParticipantResult.SUCCESS);
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            fixture.member().participantId()
        )).isEmpty();
        assertThat(connectionRepository.isAlive(fixture.member().participantId()))
            .isFalse();
        assertThat(participantRepository.findCurrentRoomId(
            fixture.member().participantId()
        )).isEmpty();

        // 같은 participantId의 재입장은 차단, 닉네임은 해제돼 새 참가자가 쓸 수 있다.
        assertThat(participantRepository.tryAdd(
            fixture.room().roomId(),
            participant(fixture.member().participantId(), "참가자", BASE_TIME.plusSeconds(9))
        )).isEqualTo(JoinParticipantResult.BANNED);
        assertThat(participantRepository.tryAdd(
            fixture.room().roomId(),
            participant(UUID.randomUUID(), "참가자", BASE_TIME.plusSeconds(10))
        )).isEqualTo(JoinParticipantResult.SUCCESS);
    }

    @Test
    void rejectsKickByNonHostAndKeepsParticipant() {
        Fixture fixture = createRoomWithHostAndMember();

        KickParticipantResult result = participantRepository.kick(
            fixture.room().roomId(),
            fixture.member().participantId(),
            fixture.host().participantId()
        );

        assertThat(result).isEqualTo(KickParticipantResult.NOT_HOST);
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            fixture.host().participantId()
        )).isPresent();
    }

    @Test
    void rejectsSelfKickAndUnknownTarget() {
        Fixture fixture = createRoomWithHostAndMember();

        assertThat(participantRepository.kick(
            fixture.room().roomId(),
            fixture.host().participantId(),
            fixture.host().participantId()
        )).isEqualTo(KickParticipantResult.SELF_KICK);
        assertThat(participantRepository.kick(
            fixture.room().roomId(),
            fixture.host().participantId(),
            UUID.randomUUID()
        )).isEqualTo(KickParticipantResult.PARTICIPANT_NOT_FOUND);
    }

    @Test
    void rejectsKickAfterGameStarted() {
        Fixture fixture = createRoomWithHostAndMember();
        roomRepository.updateStatus(fixture.room().roomId(), RoomStatus.PLAYING);

        KickParticipantResult result = participantRepository.kick(
            fixture.room().roomId(),
            fixture.host().participantId(),
            fixture.member().participantId()
        );

        assertThat(result).isEqualTo(KickParticipantResult.ROOM_ALREADY_STARTED);
        assertThat(participantRepository.findById(
            fixture.room().roomId(),
            fixture.member().participantId()
        )).isPresent();
    }

    @Test
    void deletesBanListWhenRoomDies() {
        Fixture fixture = createRoomWithHostAndMember();
        participantRepository.kick(
            fixture.room().roomId(),
            fixture.host().participantId(),
            fixture.member().participantId()
        );
        String bannedKey = "room:" + fixture.room().roomId() + ":banned";
        assertThat(redisTemplate.hasKey(bannedKey)).isTrue();

        // 마지막 참가자(방장)가 나가면 방과 함께 강퇴 명단도 사라져야 한다 —
        // 안 지우면 방 생명주기 밖에 고아 키가 남는다.
        participantRepository.leave(
            fixture.room().roomId(),
            fixture.host().participantId()
        );

        assertThat(roomRepository.findById(fixture.room().roomId())).isEmpty();
        assertThat(redisTemplate.hasKey(bannedKey)).isFalse();
    }

    private Fixture createRoomWithHostAndMember() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Room room = new Room(
            roomId,
            "KB23CD",
            hostId,
            4,
            RoomStatus.WAITING,
            1,
            BASE_TIME
        );
        Participant host = participant(hostId, "방장", BASE_TIME);
        assertThat(roomRepository.tryCreate(room, host)).isTrue();
        Participant member = participant(
            UUID.randomUUID(),
            "강퇴대상",
            BASE_TIME.plusSeconds(1)
        );
        assertThat(participantRepository.tryAdd(roomId, member))
            .isEqualTo(JoinParticipantResult.SUCCESS);
        return new Fixture(room, host, member);
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

    private record Fixture(Room room, Participant host, Participant member) {
    }
}
