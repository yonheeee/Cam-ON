package com.camon.domain.course.dto;

// 게임/주제의 이름까지 함께 내려 대기방이 추가 조회 없이 코스를 그릴 수 있게 한다
// (WS로 전파되는 코스 변경 payload도 같은 DTO를 쓰므로 자기 설명적이어야 한다).
public record CourseItemResponse(
    int idx,
    Long gameId,
    String gameName,
    int roundCount,
    Long topicId,
    String topicName
) {
}
