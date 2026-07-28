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

    @Test
    void rejectsRoundCountOtherThanOne() {
        UUID participantId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 2, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("totalRounds must be 1");
        assertThatThrownBy(() -> repository.initialize(
            "CH4R4D", 1, 3, 7L, List.of(participantId)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("totalRounds must be 1");
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
