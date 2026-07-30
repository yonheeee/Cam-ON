package com.camon.domain.game.common.repository;

import java.util.Map;
import java.util.UUID;

public interface GameResultRepository {

    SaveRoundResult saveRoundResults(
        UUID roomId,
        int sessionSeq,
        int round,
        Map<UUID, Long> scores
    );

    Map<UUID, Long> findRoundResults(
        UUID roomId,
        int sessionSeq,
        int round
    );

    Map<UUID, Long> findSessionTotals(UUID roomId, int sessionSeq);

    Map<UUID, Long> findCourseTotals(UUID roomId);

    /**
     * 코스 한 번의 점수 기록 전부(course:totals + 모든 session:{seq}:* 키)를 지운다.
     * 점수 키는 HINCRBY로 누적되므로, 같은 방에서 코스를 다시 돌리려면 반드시 먼저 지워야
     * 이전 코스 점수가 얹히지 않는다.
     */
    void clearCourseResults(UUID roomId);
}
