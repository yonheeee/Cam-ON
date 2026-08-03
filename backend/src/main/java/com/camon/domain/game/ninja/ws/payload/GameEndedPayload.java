package com.camon.domain.game.ninja.ws.payload;

import com.camon.domain.game.ninja.dto.RankingEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 최종 순위와 누적 점수. 순위는 누적 점수순이고, 종료 화면이 "n위 — 닉네임 · m점"을 그리는 데
// 두 값이 모두 필요해서 함께 싣는다(폴링 없이 이벤트만으로 종료 화면을 완성할 수 있게).
public record GameEndedPayload(
    List<RankingEntry> ranking,
    Map<String, Long> sessionTotals,
    Map<UUID, Long> courseTotals
) {
}
