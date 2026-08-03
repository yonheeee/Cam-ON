package com.camon.domain.game.common.dto;

// 코스의 첫 게임이 열렸다는 응답. 화면 전환 자체는 game:started WS 이벤트로 하지만,
// 요청이 무엇을 열었는지는 응답으로도 확인할 수 있게 둔다.
public record StartGameResponse(
    Long gameId,
    int sessionSeq,
    int totalRounds
) {
}
