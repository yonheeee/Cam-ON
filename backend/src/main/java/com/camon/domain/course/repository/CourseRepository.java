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

    // 코스의 한 칸을 실제 세션으로 연다 — room:{code}:session:{seq}에 game_id/total_rounds를
    // 코스에서 그대로 복사한다(설계 문서의 "session:{seq}가 course:{seq}를 읽어 복사" 그대로).
    //
    // 게임이 아니라 코스가 이 키를 만드는 이유: 공통 점수 저장(GameResultRepository)이 이 키의
    // 존재로 "세션이 열렸는지"를 검증하는데, 게임마다 각자 만들게 두면 빠뜨린 게임에서
    // 점수 저장이 통째로 실패한다(몸으로 말해요가 실제로 그랬다).
    void openSession(String roomCode, int sessionSeq, Long gameId, int totalRounds);
}
