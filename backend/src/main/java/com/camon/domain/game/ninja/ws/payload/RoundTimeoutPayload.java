package com.camon.domain.game.ninja.ws.payload;

import com.camon.domain.game.ninja.domain.NinjaPhase;
import com.camon.domain.game.ninja.dto.RoundResultEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 라운드 제한시간 내에 아무도 콤보를 완성 못해 공격 없이 종료된 경우. 이펙트는 없고(재생할 공격이
// 없음) 다음 라운드 직전 3초 카운트다운만 태운다 — nextRoundAt이 그 종료 시각. 이 타임아웃이
// 게임을 끝내는 경우(마지막 라운드 등)엔 카운트다운 없이 바로 종료되며 phase=ENDED, nextRoundAt=null.
// roundResult/sessionTotals는 이 타임아웃이 판을 끝냈을 때(교환 상한 도달)만 채워진다 — 아니면 null.
public record RoundTimeoutPayload(
    int round,
    int exchange,
    NinjaPhase phase,
    Instant nextRoundAt,
    List<RoundResultEntry> roundResult,
    Map<String, Long> sessionTotals
) {
}
