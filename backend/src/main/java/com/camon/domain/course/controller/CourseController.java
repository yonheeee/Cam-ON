package com.camon.domain.course.controller;

import com.camon.domain.course.dto.CourseResponse;
import com.camon.domain.course.dto.UpdateCourseRequest;
import com.camon.domain.course.service.CourseService;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/course")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public ApiResponse<CourseResponse> getCourse(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId
    ) {
        return ApiResponse.ok(
            courseService.getCourse(roomId, principal.participantId())
        );
    }

    // PATCH가 아니라 PUT인 이유: 항목 단위 수정이 아니라 코스 전체를 갈아끼운다
    // (순서가 리스트 순서로만 표현되므로 부분 수정에 의미를 부여하기 어렵다).
    @PutMapping
    public ApiResponse<CourseResponse> updateCourse(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId,
        @Valid @RequestBody UpdateCourseRequest request
    ) {
        return ApiResponse.ok(courseService.updateCourse(
            roomId,
            principal.participantId(),
            request
        ));
    }
}
