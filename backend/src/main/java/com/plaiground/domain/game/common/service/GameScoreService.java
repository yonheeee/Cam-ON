package com.plaiground.domain.game.common.service;

import com.plaiground.domain.game.common.repository.GameResultRepository;
import com.plaiground.domain.game.common.repository.SaveRoundResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GameScoreService {

    private static final List<Long> POINTS_BY_RANK = List.of(5L, 4L, 3L, 2L);

    private final GameResultRepository gameResultRepository;

    public GameScoreService(GameResultRepository gameResultRepository) {
        this.gameResultRepository = gameResultRepository;
    }

    public SaveRoundResult saveRoundRanking(
        UUID roomId,
        int sessionSeq,
        int round,
        List<UUID> participantIdsByRank
    ) {
        validateRanking(participantIdsByRank);

        LinkedHashMap<UUID, Long> scores = new LinkedHashMap<>();
        for (int index = 0; index < participantIdsByRank.size(); index++) {
            scores.put(participantIdsByRank.get(index), POINTS_BY_RANK.get(index));
        }
        return gameResultRepository.saveRoundResults(
            roomId,
            sessionSeq,
            round,
            scores
        );
    }

    public Map<UUID, Long> getRoundResults(
        UUID roomId,
        int sessionSeq,
        int round
    ) {
        return gameResultRepository.findRoundResults(roomId, sessionSeq, round);
    }

    public Map<UUID, Long> getSessionTotals(UUID roomId, int sessionSeq) {
        return gameResultRepository.findSessionTotals(roomId, sessionSeq);
    }

    public Map<UUID, Long> getCourseTotals(UUID roomId) {
        return gameResultRepository.findCourseTotals(roomId);
    }

    private static void validateRanking(List<UUID> participantIdsByRank) {
        if (participantIdsByRank == null || participantIdsByRank.isEmpty()) {
            throw new IllegalArgumentException("ranking must not be empty");
        }
        if (participantIdsByRank.size() > POINTS_BY_RANK.size()) {
            throw new IllegalArgumentException("ranking supports up to 4 participants");
        }
        if (participantIdsByRank.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("ranking must not contain null");
        }
        if (new LinkedHashSet<>(participantIdsByRank).size()
            != participantIdsByRank.size()) {
            throw new IllegalArgumentException(
                "ranking must not contain duplicate participants"
            );
        }
    }
}
