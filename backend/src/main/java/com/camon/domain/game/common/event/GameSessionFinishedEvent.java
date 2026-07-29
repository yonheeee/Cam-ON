package com.camon.domain.game.common.event;

import java.util.UUID;

// "코스의 seq번째 게임이 끝났다". 게임 도메인이 발행하고 코스 도메인이 받아 다음 게임을 연다.
//
// 게임이 코스를 직접 호출하지 않고 이벤트로 알리는 이유: 코스는 게임 세션을 열기 위해 게임
// 도메인을 의존하는데(GameSessionStarter), 게임이 코스를 되불러 의존하면 순환이 된다.
// room 도메인이 ParticipantLeftEvent로 게임 도메인에 이탈을 알리는 것과 같은 패턴이다.
public record GameSessionFinishedEvent(
    UUID roomId,
    int sessionSeq
) {
}
