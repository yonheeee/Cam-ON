package com.camon.domain.room.domain;

import java.time.Instant;
import java.util.UUID;

public record Room(
    UUID roomId,
    String roomCode,
    UUID hostParticipantId,
    int maxPlayers,
    RoomStatus status,
    // 코스에서 진행 중인 세션 위치(1부터) — room:{code}.current_session_seq. 게임 도메인이 이 값과
    // roomCode로 room:{code}:session:{seq}:... 키를 조립한다(예: domain/game/ninja).
    int currentSessionSeq,
    // 발표 시연용 방인가. 켜져 있으면 게임별 콘텐츠 선택이 랜덤 대신 DemoScenario의 고정
    // 시나리오로 바뀐다(닌자 술법/데미지, 물건 제시어 수, 몸으로말해요 제시어). 방 생성 시에만
    // 정해지고 이후 바뀌지 않는다 — 진행 중에 규칙이 바뀌면 그게 더 사고다.
    boolean demoMode,
    Instant createdAt
) {

    // 시연 모드가 아닌 평범한 방. demoMode를 신경 쓸 필요가 없는 호출부(대부분의 테스트,
    // 시연과 무관한 생성 경로)가 인자 하나를 더 끌고 다니지 않게 남겨 둔 생성자다.
    public Room(
        UUID roomId,
        String roomCode,
        UUID hostParticipantId,
        int maxPlayers,
        RoomStatus status,
        int currentSessionSeq,
        Instant createdAt
    ) {
        this(
            roomId,
            roomCode,
            hostParticipantId,
            maxPlayers,
            status,
            currentSessionSeq,
            false,
            createdAt
        );
    }
}
