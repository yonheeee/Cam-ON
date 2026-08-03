package com.camon.domain.game.common.service;

import com.camon.domain.game.common.repository.GameResultRepository;
import com.camon.domain.game.common.repository.SaveRoundResult;
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

    public SaveRoundResult saveRoundScores(
        UUID roomId,
        int sessionSeq,
        int round,
        Map<UUID, Long> scores
    ) {
        validateScores(scores);
        return gameResultRepository.saveRoundResults(
            roomId,
            sessionSeq,
            round,
            new LinkedHashMap<>(scores)
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

    /** 코스 재시작 준비 — 이전 코스의 점수 기록을 전부 지운다 (대기방 복귀 시 호출). */
    public void clearCourseResults(UUID roomId) {
        gameResultRepository.clearCourseResults(roomId);
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

    private static void validateScores(Map<UUID, Long> scores) {
        if (scores == null || scores.isEmpty()) {
            throw new IllegalArgumentException("scores must not be empty");
        }
        if (scores.size() > POINTS_BY_RANK.size()) {
            throw new IllegalArgumentException("scores support up to 4 participants");
        }
        if (scores.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null
                || entry.getValue() == null
                || entry.getValue() < 0
        )) {
            throw new IllegalArgumentException(
                "scores must contain non-null participants and non-negative points"
            );
        }
    }
}
