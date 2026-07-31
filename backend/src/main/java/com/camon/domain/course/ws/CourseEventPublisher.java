package com.camon.domain.course.ws;

import com.camon.domain.course.dto.CourseResponse;
import com.camon.domain.course.ws.payload.CourseFinishedPayload;
import com.camon.domain.course.ws.payload.CourseSessionSkippedPayload;
import com.camon.domain.course.ws.payload.MemberReturnedPayload;
import com.camon.global.ws.StompBroadcaster;
import com.camon.global.ws.StompEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CourseEventPublisher {

    // API 명세서의 "게임 선택 변경 전파" 이벤트 이름. 방장이 코스를 저장하면 대기방에 있는
    // 전원이 이걸 받아 같은 구성을 보게 된다(비방장은 읽기 전용으로 렌더).
    private static final String COURSE_UPDATED_EVENT = "member:game-updated";

    private final StompBroadcaster broadcaster;

    public CourseEventPublisher(StompBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    // payload로 변경분(diff)이 아니라 코스 전체를 보낸다 — 저장 자체가 전체 교체라
    // 부분 갱신을 표현할 방법이 없고, 받는 쪽도 그냥 통째로 갈아끼우면 되어 단순하다.
    public void publishCourseUpdated(UUID roomId, CourseResponse course) {
        publish(roomId, COURSE_UPDATED_EVENT, course);
    }

    public void publishCourseFinished(
        UUID roomId,
        CourseFinishedPayload payload
    ) {
        publish(roomId, "course:finished", payload);
    }

    // 참가자 한 명이 코스 종합 결과에서 대기방 복귀를 눌렀다. 복귀는 개별 행동이라 이 이벤트로
    // 화면을 접는 건 payload의 당사자뿐이고, 나머지는 그 사람 타일의 "게임 중" 표시만 뗀다.
    public void publishMemberReturned(
        UUID roomId,
        MemberReturnedPayload payload
    ) {
        publish(roomId, "course:member-returned", payload);
    }

    public void publishSessionSkipped(
        UUID roomId,
        CourseSessionSkippedPayload payload
    ) {
        publish(roomId, "course:session-skipped", payload);
    }

    private void publish(UUID roomId, String eventName, Object payload) {
        broadcaster.send(
            "/topic/rooms/" + roomId,
            new StompEvent<>(
                UUID.randomUUID(),
                eventName,
                roomId,
                Instant.now(),
                payload
            )
        );
    }
}
