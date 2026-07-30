package com.camon.domain.game.ninja.ws.payload;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import com.camon.domain.game.ninja.dto.RoundResultEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 교환 제한시간 내에 아무도 콤보를 완성 못한 경우. 이펙트는 없고(재생할 공격이 없음) 다음 교환
// 직전 3초 카운트다운만 태운다 — nextRoundAt이 그 종료 시각. 이 타임아웃이 게임을 끝내는
// 경우엔 카운트다운 없이 바로 종료되며 phase=ENDED, nextRoundAt=null.
//
// 타임아웃마다 생존자 전원의 HP가 감쇠(TIMEOUT_HP_DECAY)되므로, 이벤트만 구독하는 클라이언트가
// 화면 HP를 맞출 수 있게 감쇠 반영 후의 alivePlayers/hp 스냅샷과 이번 감쇠로 탈락한 목록을
// 함께 싣는다 (round-started의 스냅샷과 같은 형태).
// roundResult/sessionTotals는 이 타임아웃이 판을 끝냈을 때만 채워진다 — 아니면 null.
public record RoundTimeoutPayload(
    int round,
    int exchange,
    NinjaPhase phase,
    Instant nextRoundAt,
    List<RoundResultEntry> roundResult,
    Map<String, Long> sessionTotals,
    List<String> alivePlayers,
    Map<String, Integer> hp,
    List<String> eliminated
) {
}
