package com.camon.domain.course.repository;

public enum CourseReplaceResult {
    SUCCESS,
    ROOM_NOT_FOUND,
    // 게임이 시작된 뒤에는 코스를 바꿀 수 없다.
    ROOM_NOT_WAITING
}
