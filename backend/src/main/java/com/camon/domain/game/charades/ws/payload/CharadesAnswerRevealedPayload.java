package com.camon.domain.game.charades.ws.payload;

import java.time.Instant;
import java.util.UUID;

// word는 이번 턴의 제시어 원본이다. 정답자가 친 텍스트로 대신할 수 없다 —
// CharadesAnswerMatcher는 공백·영문 대소문자·유니코드 정규화만 무시하고 비교하므로
// 제시어가 "babyshark"여도 "Baby Shark"가 정답이 된다. 그 텍스트를 제시어인 양 보여주면
// 실제와 다른 값을 공개하게 된다.
// (특수문자·이모지는 무시하지 않는다 — "아기-상어!"는 "아기상어"의 정답이 아니다.)
public record CharadesAnswerRevealedPayload(
    int round,
    int turn,
    UUID presenterId,
    UUID answererId,
    Instant answeredAt,
    String word
) {
}
