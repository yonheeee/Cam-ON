package com.camon.domain.course.ws.payload;

// 코스에 담긴 게임을 현재 인원으로 진행할 수 없어 건너뛸 때 발행된다.
// (예: 4명으로 시작했는데 2명이 나가서 몸으로 말해요 최소 3명을 못 채우는 경우)
//
// 코스를 중단하지 않고 건너뛰는 이유: 남은 사람들이 할 수 있는 게임은 계속 할 수 있어야 하고,
// 게임 하나 때문에 방을 끝내버리는 게 더 파괴적이다.
public record CourseSessionSkippedPayload(
    int sessionSeq,
    Long gameId,
    String gameName,
    String reason
) {
}
