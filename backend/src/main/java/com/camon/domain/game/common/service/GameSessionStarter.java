package com.camon.domain.game.common.service;

import com.camon.domain.room.domain.Participant;
import java.util.List;
import java.util.UUID;

// 코스가 다음 칸으로 넘어갈 때 "그 게임의 세션을 여는" 방법. 게임을 추가할 때 이 인터페이스만
// 구현하면 코스 진행 쪽은 손대지 않아도 된다(물건 가져오기가 생기면 여기에 하나 더 붙는다).
//
// 구현체는 각 게임 도메인 안에 둔다 — 세션을 어떻게 여는지는 그 게임만 아는 지식이다.
public interface GameSessionStarter {

    // games.name과 정확히 같은 문자열("NINJA" / "CHARADES" / "FETCH_OBJECT").
    // game_id로 매칭하지 않는 이유: id는 시드 순서에 따라 환경마다 달라질 수 있어 코드에 박을 수 없다.
    String gameName();

    void start(UUID roomId, GameSessionSpec spec, List<Participant> participants);
}
