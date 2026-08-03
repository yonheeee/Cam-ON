package com.camon.domain.game.common.service;

import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.event.ParticipantLeftEvent;
import com.camon.domain.room.repository.ParticipantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// room 도메인의 퇴장(ParticipantLeftEvent)을 게임 도메인으로 넘기는 단일 창구.
//
// 게임마다 리스너를 따로 두면 "새 게임을 추가했는데 퇴장 처리만 빠뜨리는" 실수가 나기 쉽다 —
// 실제로 몸으로 말해요만 리스너가 있어서, 닌자는 떠난 사람이 생존자 집합에 남아 판이 끝나지
// 않았다. 등록된 구현체 전부에게 알리고, 자기 세션이 아닌 구현체는 스스로 no-op 한다.
@Component
public class GameParticipantEventListener {

    private static final Logger log =
        LoggerFactory.getLogger(GameParticipantEventListener.class);

    private final ParticipantRepository participantRepository;
    private final List<GameParticipantLeaveHandler> handlers;

    public GameParticipantEventListener(
        ParticipantRepository participantRepository,
        List<GameParticipantLeaveHandler> handlers
    ) {
        this.participantRepository = participantRepository;
        this.handlers = handlers;
    }

    @EventListener
    public void onParticipantLeft(ParticipantLeftEvent event) {
        // 이벤트가 발행되는 시점엔 이미 Redis 참가자 집합에서 빠진 뒤라, 이 값은 "떠난 사람을
        // 제외하고 남은 인원"이다.
        int connectedCount = countConnected(event.roomId());
        for (GameParticipantLeaveHandler handler : handlers) {
            try {
                handler.handleParticipantLeft(
                    event.roomId(),
                    event.participantId(),
                    event.reason(),
                    connectedCount
                );
            } catch (RuntimeException e) {
                // 한 게임의 뒷정리가 실패해도 나머지는 돌아야 한다. 특히 이 리스너는 퇴장
                // 처리(RoomService.leaveRoom) 흐름 위에서 도는데, 여기서 예외가 새면 퇴장
                // 자체가 실패한 것처럼 보인다.
                log.warn(
                    "[Listener] onParticipantLeft : {} 처리 실패 roomId={} participantId={}",
                    handler.getClass().getSimpleName(),
                    event.roomId(),
                    event.participantId(),
                    e
                );
            }
        }
    }

    private int countConnected(java.util.UUID roomId) {
        int connected = 0;
        for (Participant participant : participantRepository.findAll(roomId)) {
            if (participant.connectionStatus() == ConnectionStatus.CONNECTED) {
                connected++;
            }
        }
        return connected;
    }
}
