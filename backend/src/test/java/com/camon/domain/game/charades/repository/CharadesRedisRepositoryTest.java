package com.camon.domain.game.charades.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class CharadesRedisRepositoryTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CharadesRedisRepository repository =
        new CharadesRedisRepository(redis);

    // 라운드 수는 코스가 정하므로 2, 3라운드도 정상이다(예전 단일 라운드 정책 때는 1만 허용했다).
    // 리포지토리는 저장 자체가 성립하지 않는 값(1 미만)만 막는다.
    @Test
    void rejectsRoundCountBelowOne() {
        UUID participantId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 0, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("totalRounds must be at least 1");
        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, -1, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("totalRounds must be at least 1");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsInvalidPresenterOrder() {
        UUID participantId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 1, 7L, List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("presenterOrder must not be empty");
        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 1, 7L, List.of(participantId, participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("presenterOrder must not contain duplicates");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsInvalidTopicBeforeCallingRedis() {
        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 1, 0L, List.of(UUID.randomUUID())
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("topicId must be at least 1");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsInvalidTurnDataBeforeCallingRedis() {
        assertThatThrownBy(() -> repository.openTurn(
            "CH4R4D", 1, 0, 1, UUID.randomUUID(), 1L, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("round must be at least 1");
        assertThatThrownBy(() -> repository.openTurn(
            "CH4R4D", 1, 1, 0, UUID.randomUUID(), 1L, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("turn must be at least 1");
        assertThatThrownBy(() -> repository.openTurn(
            "CH4R4D", 1, 1, 1, UUID.randomUUID(), 0L, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("missionId must be at least 1");
        verifyNoInteractions(redis);
    }
}
