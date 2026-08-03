package com.camon.domain.game.charades.ws.payload;

// word는 아무도 못 맞힌 채 끝난 이번 턴의 제시어다. 턴이 끝나면 GET /word로는 아무도(표현자
// 포함) 다시 가져올 수 없으므로, 전원에게 정답을 공개하는 경로는 이 payload뿐이다.
// 제시어를 찾지 못하면 null이 온다 — 그 경우에도 턴 전개는 멈추지 않는다.
public record CharadesRoundTimeoutPayload(
    int round,
    int turn,
    String word
) {
}
