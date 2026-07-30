package com.camon.domain.course.ws.payload;

import java.util.UUID;

// 방장이 코스 종합 결과에서 "방으로 돌아가기"를 눌러 방이 WAITING으로 되돌아갔다.
// 받은 클라이언트는 게임/결과 화면을 접고 대기방으로 전환한다(준비 상태는 전원 해제됨 —
// 대기방 스냅샷을 다시 읽으면 반영돼 있다).
public record CourseResetPayload(UUID byParticipantId) {
}
