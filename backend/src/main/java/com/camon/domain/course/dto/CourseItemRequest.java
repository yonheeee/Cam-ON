package com.camon.domain.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CourseItemRequest(
    @NotNull @Positive Long gameId,
    @NotNull @Min(1) Integer roundCount,
    // 주제를 고르는 게임(몸으로 말해요)만 필수. 나머지는 null이어야 한다 —
    // 게임별 허용 범위는 형식 검증으로 표현할 수 없어 서비스가 검증한다.
    Long topicId
) {
}
