package com.camon.domain.course.ws.payload;

import java.util.List;

// 코스의 마지막 게임까지 끝났을 때 한 번 발행된다. 프론트는 이걸 받아 종합 결과 화면으로 간다
// (게임별 game-ended와 구분: 그건 "이 게임이 끝났다", 이건 "이 방의 모든 게임이 끝났다").
public record CourseFinishedPayload(
    // 실제로 진행된 게임 수(건너뛴 게임은 제외되지 않는다 — 코스에 담긴 총 칸 수).
    int totalSessions,
    // 코스 전체 누적 점수(room:{code}:course:totals) 기준 최종 순위.
    List<CourseScoreEntry> ranking
) {
}
