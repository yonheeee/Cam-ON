package com.camon.domain.room.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 방 안의 참가자 한 명.
 *
 * <p>{@code inLobby}는 "지금 대기방 화면에 있는가"다. 코스가 시작되면 전원 false가 되고,
 * 코스 종합 결과에서 각자 "방으로 돌아가기"를 누른 사람만 다시 true가 된다 — 복귀는 개별
 * 행동이라 한 명이 눌러도 나머지는 결과 화면에 남는다. 아직 false인 참가자도 방을 떠난 것이
 * 아니므로 대기방 타일에 자리를 그대로 지키고 "게임 중"으로 표시된다(방장 자격과 입장 순서도
 * 그대로 유지된다).
 */
public record Participant(
    UUID participantId,
    String nickname,
    boolean ready,
    ConnectionStatus connectionStatus,
    Instant joinedAt,
    boolean inLobby
) {

    /** 새로 입장하는 참가자는 대기방에서 시작한다. */
    public Participant(
        UUID participantId,
        String nickname,
        boolean ready,
        ConnectionStatus connectionStatus,
        Instant joinedAt
    ) {
        this(participantId, nickname, ready, connectionStatus, joinedAt, true);
    }
}
