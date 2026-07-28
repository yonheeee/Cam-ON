package com.camon.domain.course.ws.payload;

import java.util.UUID;

// 코스 종합 결과 한 줄. 동점자는 같은 순위를 갖는다.
public record CourseScoreEntry(
    UUID participantId,
    long totalScore,
    int rank
) {
}
