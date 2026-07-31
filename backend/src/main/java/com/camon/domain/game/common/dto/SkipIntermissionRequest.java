package com.camon.domain.game.common.dto;

import jakarta.validation.constraints.Min;

/**
 * 인터미션 "바로 시작" 요청.
 *
 * @param finishedSessionSeq 건너뛰려는 인터미션이 어느 게임 뒤의 것인지 —
 *                           {@code course:intermission}으로 받은 값을 그대로 돌려보낸다.
 *                           서버의 현재 seq와 다르면 이미 다음 게임이 열린 뒤의 늦은 클릭이므로
 *                           거절한다(그냥 "지금 넘겨"로 만들면 방금 시작한 게임을 날려버린다).
 */
public record SkipIntermissionRequest(
    @Min(1) int finishedSessionSeq
) {
}
