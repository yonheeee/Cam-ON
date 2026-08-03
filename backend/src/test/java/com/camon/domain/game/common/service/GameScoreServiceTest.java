package com.camon.domain.game.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.common.repository.GameResultRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameScoreServiceTest {

    @Mock
    private GameResultRepository gameResultRepository;

    @InjectMocks
    private GameScoreService gameScoreService;

    @Test
    void convertsRankingToPointsAndSavesRoundResults() {
        UUID roomId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        when(gameResultRepository.saveRoundResults(
            org.mockito.ArgumentMatchers.eq(roomId),
            org.mockito.ArgumentMatchers.eq(2),
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.anyMap()
        )).thenReturn(SaveRoundResult.SUCCESS);

        SaveRoundResult result = gameScoreService.saveRoundRanking(
            roomId,
            2,
            3,
            List.of(first, second, third, fourth)
        );

        assertThat(result).isEqualTo(SaveRoundResult.SUCCESS);
        verify(gameResultRepository).saveRoundResults(
            roomId,
            2,
            3,
            Map.of(first, 5L, second, 4L, third, 3L, fourth, 2L)
        );
    }

    @Test
    void usesHighestPointsWhenFewerThanFourParticipantsAreRanked() {
        UUID roomId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(gameResultRepository.saveRoundResults(
            org.mockito.ArgumentMatchers.eq(roomId),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.anyMap()
        )).thenReturn(SaveRoundResult.SUCCESS);

        gameScoreService.saveRoundRanking(
            roomId,
            1,
            1,
            List.of(first, second)
        );

        verify(gameResultRepository).saveRoundResults(
            roomId,
            1,
            1,
            Map.of(first, 5L, second, 4L)
        );
    }

    @Test
    void savesGameSpecificScoresWithoutRankConversion() {
        UUID roomId = UUID.randomUUID();
        UUID presenter = UUID.randomUUID();
        UUID answerer = UUID.randomUUID();
        Map<UUID, Long> scores = Map.of(presenter, 1L, answerer, 1L);
        when(gameResultRepository.saveRoundResults(roomId, 1, 2, scores))
            .thenReturn(SaveRoundResult.SUCCESS);

        SaveRoundResult result = gameScoreService.saveRoundScores(
            roomId,
            1,
            2,
            scores
        );

        assertThat(result).isEqualTo(SaveRoundResult.SUCCESS);
        verify(gameResultRepository).saveRoundResults(roomId, 1, 2, scores);
    }

    @Test
    void convertsFinalRanksToNormalizedCoursePoints() {
        UUID roomId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        Map<UUID, Integer> ranks = Map.of(first, 1, second, 2, third, 3, fourth, 4);
        when(gameResultRepository.saveCourseResults(
            roomId, 2, Map.of(first, 5L, second, 3L, third, 2L, fourth, 1L)
        )).thenReturn(SaveRoundResult.SUCCESS);

        assertThat(gameScoreService.saveCourseRanking(roomId, 2, ranks))
            .isEqualTo(SaveRoundResult.SUCCESS);
        verify(gameResultRepository).saveCourseResults(
            roomId, 2, Map.of(first, 5L, second, 3L, third, 2L, fourth, 1L)
        );
    }

    @Test
    void givesTiedParticipantsTheSameNormalizedPoints() {
        UUID roomId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID tiedFirst = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(gameResultRepository.saveCourseResults(
            roomId, 1, Map.of(first, 5L, tiedFirst, 5L, third, 2L)
        )).thenReturn(SaveRoundResult.SUCCESS);

        gameScoreService.saveCourseRanking(
            roomId, 1, Map.of(first, 1, tiedFirst, 1, third, 3)
        );

        verify(gameResultRepository).saveCourseResults(
            roomId, 1, Map.of(first, 5L, tiedFirst, 5L, third, 2L)
        );
    }

    @Test
    void rejectsInvalidRankings() {
        UUID participantId = UUID.randomUUID();

        assertThatThrownBy(() -> gameScoreService.saveRoundRanking(
            UUID.randomUUID(),
            1,
            1,
            List.of()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gameScoreService.saveRoundRanking(
            UUID.randomUUID(),
            1,
            1,
            List.of(participantId, participantId)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gameScoreService.saveRoundRanking(
            UUID.randomUUID(),
            1,
            1,
            List.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
            )
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
