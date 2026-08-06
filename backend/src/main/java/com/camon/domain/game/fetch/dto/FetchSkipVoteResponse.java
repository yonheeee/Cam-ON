package com.camon.domain.game.fetch.dto;

/** 스킵 투표 REST 응답. 브로드캐스트(round:skip-voted)와 같은 숫자를 즉시 돌려준다. */
public record FetchSkipVoteResponse(
    int round,
    int votes,
    int required
) {
}
