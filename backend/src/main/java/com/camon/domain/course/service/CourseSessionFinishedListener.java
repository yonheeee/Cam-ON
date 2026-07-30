package com.camon.domain.course.service;

import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 게임 종료 → 코스 진행을 잇는 유일한 연결점. 게임 도메인이 코스를 직접 호출하지 않게
// 이벤트로 끊어 둔 자리다(CharadesParticipantEventListener와 같은 역할).
@Component
public class CourseSessionFinishedListener {

    private final CourseRunner courseRunner;

    public CourseSessionFinishedListener(CourseRunner courseRunner) {
        this.courseRunner = courseRunner;
    }

    @EventListener
    public void onGameSessionFinished(GameSessionFinishedEvent event) {
        courseRunner.scheduleAdvance(event.roomId(), event.sessionSeq());
    }
}
