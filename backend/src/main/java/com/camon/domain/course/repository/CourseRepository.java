package com.camon.domain.course.repository;

import com.camon.domain.course.domain.CourseItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseRepository {

    // 코스를 통째로 교체한다(부분 수정 없음) — 순서·개수·라운드 수가 한 덩어리로만 의미를 갖고,
    // 4명이 동시에 편집할 화면도 아니라서 항목 단위 CRUD보다 이게 단순하고 안전하다.
    // 방이 WAITING이 아니면 거부한다(진행 중인 코스가 발밑에서 바뀌는 것을 막는다).
    CourseReplaceResult replace(UUID roomId, String roomCode, List<CourseItem> items);

    List<CourseItem> findAll(UUID roomId, String roomCode);

    Optional<CourseItem> find(UUID roomId, String roomCode, int idx);
}
