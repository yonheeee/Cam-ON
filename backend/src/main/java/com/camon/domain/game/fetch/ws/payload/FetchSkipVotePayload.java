package com.camon.domain.game.fetch.ws.payload;

import java.util.UUID;

/**
 * 스킵 투표 현황 브로드캐스트 (round:skip-voted).
 * votes/required는 서버 계산이 원본 — 프론트 카운터는 표시만 한다.
 * required = 현재 접속 참가자 수 (퇴장자 제외).
 */
public record FetchSkipVotePayload(
    int round,
    UUID participantId,
    int votes,
    int required
) {
}
