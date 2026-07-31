package com.camon.domain.course.ws.payload;

import java.util.UUID;

/**
 * 참가자 한 명이 코스 종합 결과에서 "방으로 돌아가기"를 눌러 대기방으로 들어왔다.
 *
 * <p>복귀는 개별 행동이므로 이 이벤트는 "전원 대기방으로"라는 뜻이 아니다 — 받은 쪽은
 * {@code participantId}에 해당하는 타일만 "게임 중" 표시를 떼고, 자기 자신의 id라면 결과
 * 화면을 접고 대기방으로 전환한다.
 *
 * @param participantId 대기방으로 돌아온 참가자
 * @param ready         복귀 직후의 준비 상태. 방장은 "게임 시작"이 곧 준비 의사라 true,
 *                      나머지는 카메라·인식 테스트를 다시 거쳐야 하므로 false다.
 * @param roomReopened  이 복귀가 방을 FINISHED에서 WAITING으로 되돌렸는가(= 가장 먼저 누른
 *                      사람인가). 점수 초기화도 이때 함께 일어난다.
 */
public record MemberReturnedPayload(
    UUID participantId,
    boolean ready,
    boolean roomReopened
) {
}
