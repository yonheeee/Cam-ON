package com.camon.domain.game.common.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.event.ParticipantLeftEvent;
import com.camon.domain.room.repository.ParticipantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 퇴장 이벤트를 게임 도메인으로 넘기는 단일 창구의 계약 검증:
// 등록된 구현체 전부에게 알리고, 남은 "연결된" 인원은 한 번만 세서 공유한다.
@ExtendWith(MockitoExtension.class)
class GameParticipantEventListenerTest {

    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private GameParticipantLeaveHandler ninjaHandler;
    @Mock
    private GameParticipantLeaveHandler charadesHandler;

    private final UUID roomId = UUID.randomUUID();
    private final UUID departed = UUID.randomUUID();

    private static Participant connected(String nickname) {
        return new Participant(
            UUID.randomUUID(),
            nickname,
            false,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
    }

    private static Participant disconnected(String nickname) {
        return new Participant(
            UUID.randomUUID(),
            nickname,
            false,
            ConnectionStatus.DISCONNECTED,
            Instant.now()
        );
    }

    private GameParticipantEventListener listener() {
        return new GameParticipantEventListener(
            participantRepository,
            List.of(ninjaHandler, charadesHandler)
        );
    }

    @Test
    void notifiesEveryHandlerWithConnectedCount() {
        // 재접속 유예 중(DISCONNECTED)인 사람은 게임을 이어갈 수 없으므로 세지 않는다.
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            connected("남은1"),
            connected("남은2"),
            disconnected("끊긴사람")
        ));

        listener().onParticipantLeft(
            new ParticipantLeftEvent(roomId, departed, "LEFT")
        );

        verify(ninjaHandler).handleParticipantLeft(roomId, departed, "LEFT", 2);
        verify(charadesHandler).handleParticipantLeft(roomId, departed, "LEFT", 2);
    }

    @Test
    void keepsNotifyingOtherHandlers_whenOneThrows() {
        // 이 리스너는 퇴장 처리(RoomService.leaveRoom) 흐름 위에서 돈다 — 한 게임의 뒷정리가
        // 터졌다고 퇴장 자체가 실패하거나 다른 게임 정리가 건너뛰어지면 안 된다.
        when(participantRepository.findAll(roomId)).thenReturn(List.of(connected("남은1")));
        doThrow(new IllegalStateException("boom"))
            .when(ninjaHandler)
            .handleParticipantLeft(eq(roomId), eq(departed), eq("KICKED"), eq(1));

        listener().onParticipantLeft(
            new ParticipantLeftEvent(roomId, departed, "KICKED")
        );

        verify(charadesHandler).handleParticipantLeft(roomId, departed, "KICKED", 1);
    }
}
