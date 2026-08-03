package com.camon.domain.course.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 코스 항목 하나 = 그 게임 "1세트". 라운드 수는 클라이언트가 정하지 않는다 —
// 게임별로 고정돼 있어서(닌자 1판 / 몸말 1라운드 / 물건 5라운드) 서버가 카탈로그에서 채운다.
// 같은 게임을 여러 세트 하고 싶으면 항목을 그만큼 반복해 담는다 (예: [닌자, 닌자, 몸말]).
public record CourseItemRequest(
    @NotNull @Positive Long gameId,
    // 주제를 고르는 게임(몸으로 말해요)만 필수. 나머지는 null이어야 한다 —
    // 게임별 허용 범위는 형식 검증으로 표현할 수 없어 서비스가 검증한다.
    Long topicId
) {
}
