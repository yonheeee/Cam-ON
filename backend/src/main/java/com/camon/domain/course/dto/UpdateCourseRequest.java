package com.camon.domain.course.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

// 코스 전체 교체 요청. 리스트의 순서가 곧 게임이 진행될 순서다(별도 순서 필드를 두지 않는다).
public record UpdateCourseRequest(
    @NotEmpty @Valid List<CourseItemRequest> items
) {
}
