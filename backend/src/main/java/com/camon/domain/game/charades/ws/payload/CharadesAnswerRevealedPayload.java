package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.UUID;

// word는 이번 턴의 제시어 원본이다. 정답자가 친 텍스트로 대신할 수 없다 —
// CharadesAnswerMatcher가 NFKC 정규화·소문자화·공백 제거 후 비교하므로 "코 끼리!"도 정답이
// 되는데, 그 텍스트를 제시어인 양 보여주면 틀린 값을 공개하게 된다.
public record CharadesAnswerRevealedPayload(
    int round,
    int turn,
    UUID presenterId,
    UUID answererId,
    Instant answeredAt,
    String word
) {
}
