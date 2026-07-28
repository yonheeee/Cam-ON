package com.camon.domain.game.common.service;

// "이 세션을 어떤 조건으로 열지"를 담은 지시서. 코스 한 칸(CourseItem)을 게임 도메인이 이해할 수
// 있는 형태로 옮긴 것 — game 패키지가 course 패키지를 알지 않게 하려고 중간에 둔 타입이다.
public record GameSessionSpec(
    Long gameId,
    // 라운드 수의 의미는 게임마다 다르다(닌자=판, 몸으로말해요=전원 한 바퀴, 물건가져오기=물건 하나).
    int totalRounds,
    // 주제를 쓰는 게임만 값이 있다. 쓰지 않는 게임은 이 값을 무시한다.
    Long topicId
) {
}
