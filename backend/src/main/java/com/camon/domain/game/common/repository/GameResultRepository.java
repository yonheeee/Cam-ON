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
}
