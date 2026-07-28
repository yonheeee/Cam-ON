package com.camon.domain.game.ninja.service;

import com.camon.domain.game.common.service.GameSessionSpec;
import com.camon.domain.game.common.service.GameSessionStarter;
import com.camon.domain.room.domain.Participant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NinjaSessionStarter implements GameSessionStarter {

    public static final String GAME_NAME = "NINJA";

    private final NinjaGameService ninjaGameService;

    public NinjaSessionStarter(NinjaGameService ninjaGameService) {
        this.ninjaGameService = ninjaGameService;
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
        // 닌자는 참가자를 토큰 문자열 집합으로 다룬다. 입장 순서를 유지해야 타일 배치/순위 동점
        // 처리가 전원 동일하게 나오므로 LinkedHashSet으로 순서를 보존한다.
        Set<String> participantTokens = participants.stream()
            .map(participant -> participant.participantId().toString())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        // spec.topicId()는 닌자에 의미가 없어 쓰지 않는다(코스 검증이 애초에 null만 허용한다).
        ninjaGameService.startSession(
            roomId,
            spec.gameId(),
            participantTokens,
            spec.totalRounds()
        );
    }
}
