package com.camon.domain.game.common.service;

import java.util.UUID;

// 게임이 진행 중일 때 참가자가 방을 떠난 경우의 뒷정리. 게임을 추가할 때 이 인터페이스만
// 구현하면 퇴장 처리 쪽(GameParticipantEventListener)은 손대지 않아도 된다 —
// GameSessionStarter("세션을 어떻게 여는가")와 같은 축의, "진행 중 이탈을 어떻게 수습하는가"다.
//
// 구현체는 각 게임 도메인 안에 둔다. 이탈이 그 게임에 무슨 의미인지는 그 게임만 아는 지식이다
// (닌자는 생존자가 한 명 줄어드는 것, 몸으로 말해요는 표현자였다면 턴이 무효가 되는 것).
//
// <p><b>구현체는 자기 세션이 열려 있지 않으면 아무것도 하지 않아야 한다.</b> 리스너는 어느
// 게임이 진행 중인지 모른 채 전부에게 알리고, 각 구현체가 자기 Redis 상태의 유무로 스스로
// 판단한다. "지금 무슨 게임인지"는 코스만 아는 정보인데, game → course 의존은 순환이라
// 만들 수 없기 때문이다(GameSessionFinishedEvent 주석 참고).
public interface GameParticipantLeaveHandler {

    /**
     * @param roomId        떠난 사람이 있던 방
     * @param participantId 떠난 사람
     * @param reason        퇴장 사유("LEFT" / "KICKED" / "TIMEOUT")
     * @param connectedCount 퇴장이 반영된 뒤 방에 남아 연결돼 있는 인원. 게임마다 "이제 게임을
     *                       이어갈 수 없다"의 기준이 달라 판단은 구현체에 맡기고, 값 계산만
     *                       리스너가 한 번 해서 공유한다.
     */
    void handleParticipantLeft(
        UUID roomId,
        UUID participantId,
        String reason,
        int connectedCount
    );
}
