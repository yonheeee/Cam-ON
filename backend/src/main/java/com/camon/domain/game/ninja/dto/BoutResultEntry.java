package com.camon.domain.game.ninja.dto;

// 방금 끝난 판(bout)의 순위 한 줄 — 그 판에서의 등수와 그 판으로 얻은 점수(5/4/3/2).
// 판 종료 인터미션 동안에만 채워져 "라운드 결과" 창을 그리는 데 쓰인다.
public record BoutResultEntry(
    String token,
    int rank,
    long points
) {
}
