package com.camon.domain.game.fetch.repository;

import java.time.Instant;
import java.util.List;

// Redis에 저장된 현재 물건 가져오기 라운드의 복구용 스냅샷.
// STOMP 이벤트를 놓친 클라이언트가 GET /state로 동일한 화면 상태를 다시 만들 때 사용한다.
public record FetchObjectRoundState(
    int round,
    int totalRounds,
    String target,
    Instant startedAt,
    Instant deadlineAt,
    String status,
    List<FetchObjectSubmissionRecord> submissions
) {
}
