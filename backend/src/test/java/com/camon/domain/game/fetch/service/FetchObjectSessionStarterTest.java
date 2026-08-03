package com.camon.domain.game.fetch.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.camon.domain.game.common.service.GameSessionSpec;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FetchObjectSessionStarterTest {

    @Test
    void delegatesCourseSpecToFetchGameService() {
        FetchObjectGameService service = mock(FetchObjectGameService.class);
        FetchObjectSessionStarter starter =
            new FetchObjectSessionStarter(service);
        UUID roomId = UUID.randomUUID();
        List<Participant> participants = List.of(participant(), participant());
        GameSessionSpec spec = new GameSessionSpec(2L, 4, null);

        starter.start(roomId, spec, participants);

        verify(service).startSession(roomId, 2L, participants, 4);
    }

    private static Participant participant() {
        return new Participant(
            UUID.randomUUID(),
            "guest",
            true,
            ConnectionStatus.CONNECTED,
            Instant.parse("2026-07-29T00:00:00Z")
        );
    }
}
