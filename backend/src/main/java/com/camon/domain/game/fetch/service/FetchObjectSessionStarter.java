package com.camon.domain.game.fetch.service;

import com.camon.domain.game.common.service.GameSessionSpec;
import com.camon.domain.game.common.service.GameSessionStarter;
import com.camon.domain.room.domain.Participant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FetchObjectSessionStarter implements GameSessionStarter {

    public static final String GAME_NAME = "FETCH_OBJECT";

    private final FetchObjectGameService fetchObjectGameService;

    public FetchObjectSessionStarter(
        FetchObjectGameService fetchObjectGameService
    ) {
        this.fetchObjectGameService = fetchObjectGameService;
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
        fetchObjectGameService.startSession(
            roomId,
            spec.gameId(),
            participants,
            spec.totalRounds()
        );
    }
}
