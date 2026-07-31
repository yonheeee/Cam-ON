package com.camon.domain.game.common.controller;

import com.camon.domain.course.service.CourseRunner;
import com.camon.domain.game.common.dto.SkipIntermissionRequest;
import com.camon.domain.game.common.dto.StartGameResponse;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // 인터미션(게임 사이 대기) 화면에서 방장이 "바로 시작"을 누르는 지점. 남은 대기 시간을
    // 건너뛰고 다음 게임을 즉시 연다 — 화면 전환은 평소와 같은 game:started로 이뤄진다.
    // finishedSessionSeq는 course:intermission으로 받은 값을 그대로 돌려보낸다: 어느 인터미션을
    // 건너뛰려는지 특정해, 타이머가 이미 다음 게임을 열어버린 뒤의 늦은 클릭을 걸러낸다.
    @PostMapping("/{roomId}/course/skip-intermission")
    public ResponseEntity<Void> skipIntermission(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId,
        @Valid @RequestBody SkipIntermissionRequest request
    ) {
        courseRunner.skipIntermission(
            roomId,
            principal.participantId(),
            request.finishedSessionSeq()
        );
        return ResponseEntity.noContent().build();
    }

    // 코스 종합 결과에서 참가자가 "방으로 돌아가기"를 누르는 지점 — 방장 전용이 아니라 각자
    // 누르며, 부르는 사람만 대기방으로 돌아간다. 가장 먼저 부른 요청이 점수를 초기화하고 방을
    // WAITING으로 되돌린다 — 전파는 서비스가 course:member-returned 이벤트로 한다.
    @PostMapping("/{roomId}/return")
    public ResponseEntity<Void> returnToLobby(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId
    ) {
        courseRunner.returnToLobby(roomId, principal.participantId());
        return ResponseEntity.noContent().build();
    }
}
