package com.camon.domain.game.common.controller;

import com.camon.domain.course.service.CourseRunner;
import com.camon.domain.game.common.dto.StartGameResponse;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class GameStartController {

    private final CourseRunner courseRunner;

    public GameStartController(CourseRunner courseRunner) {
        this.courseRunner = courseRunner;
    }

    // 요청 바디가 없다: 무엇을 몇 라운드 할지는 대기방에서 확정한 코스에 이미 들어 있다.
    // (예전엔 클라이언트가 gameId/totalRounds를 실어 보냈는데, 그러면 코스와 실제 진행이
    // 어긋날 수 있어 서버가 코스만 보도록 바꿨다.)
    @PostMapping("/{roomId}/start")
    public ResponseEntity<ApiResponse<StartGameResponse>> start(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId
    ) {
        CourseRunner.StartedSession started = courseRunner.startCourse(
            roomId,
            principal.participantId()
        );
        return ResponseEntity.ok(ApiResponse.ok(new StartGameResponse(
            started.gameId(),
            started.sessionSeq(),
            started.totalRounds()
        )));
    }
}
