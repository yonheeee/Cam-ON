package com.camon.domain.course.dto;

import java.util.List;

public record CourseResponse(
    List<CourseItemResponse> items,
    // 코스에서 진행 중인 위치(1부터). 대기방(아직 시작 전)이면 1이고, 게임 중이면 몇 번째
    // 게임인지 알려준다 — 재접속한 클라이언트가 진행 상황을 복구하는 데 쓴다.
    int currentSessionSeq
) {
}
