package com.camon.domain.game.fetch.ws.payload;

import java.util.UUID;

// 제출 성공 전파. roundDeadlineAt(epoch ms)은 이 제출로 라운드 마감이 앞당겨졌을 때만 채워진다 —
// 첫 정답이 나오면 남은 시간을 그레이스(수 초)로 줄여, 먼저 맞춘 사람이 타이머를 통째로
// 기다리지 않게 한다. null이면 마감 변경 없음(클라이언트 타이머 유지).
public record FetchRoundSuccessPayload(
    UUID participantId,
    int rank,
    long score,
    Long roundDeadlineAt
) {
}
