package com.camon.domain.course.domain;

// 코스 한 칸 = "이 자리에 놓인 게임 + 라운드 수". 대기방에서 방장이 확정하면 게임이 시작될 때
// 세션이 이 값을 그대로 복사해 쓴다(room:{code}:course:{idx} → room:{code}:session:{seq}).
//
// roundCount의 의미는 게임마다 다르다 — 이 record는 숫자만 들고 있고 해석은 각 게임이 한다:
//   물건 가져오기 : 물건 하나 맞히는 것이 1라운드
//   닌자          : 전원 풀피로 시작해 최후 1인이 남을 때까지가 1라운드
//   몸으로 말해요 : 참가자 전원이 한 번씩 출제자가 되는 것이 1라운드
public record CourseItem(
    // 코스에서 몇 번째인지(1부터). 세션 seq와 1:1로 대응한다.
    int idx,
    Long gameId,
    int roundCount,
    // 주제를 골라야 하는 게임(몸으로 말해요)만 값이 있고, 나머지는 null.
    Long topicId
) {
}
