package com.camon.domain.game.charades.service;

import com.camon.domain.game.common.service.GameSessionSpec;
import com.camon.domain.game.common.service.GameSessionStarter;
import com.camon.domain.room.domain.Participant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CharadesSessionStarter implements GameSessionStarter {

    public static final String GAME_NAME = "CHARADES";

    private final CharadesGameService charadesGameService;

    public CharadesSessionStarter(CharadesGameService charadesGameService) {
        this.charadesGameService = charadesGameService;
    }

    @Override
    public String gameName() {
        return GAME_NAME;
    }

    @Override
    public void start(
        UUID roomId,
        GameSessionSpec spec,
        List<Participant> participants
    ) {
        // 참가자 목록은 넘기지 않는다 — 몸으로 말해요는 "연결된" 참가자만으로 표현자 순서를
        // 만들어야 해서(끊긴 사람에게 턴이 가면 그 턴이 무효가 된다) 서비스가 직접 조회한다.
        charadesGameService.startSession(
            roomId,
            spec.gameId(),
            spec.topicId(),
            spec.totalRounds()
        );
    }
}
