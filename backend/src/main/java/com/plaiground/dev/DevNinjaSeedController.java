package com.plaiground.dev;

import com.plaiground.domain.game.ninja.service.NinjaGameService;
import com.plaiground.global.apiresponse.ApiResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TEMP — 방장이 대기방에서 코스를 확정하고 게임을 시작하면 자동으로 열려야 할 세션을,
// 그 흐름이 없는 지금은 이 엔드포인트로 수동 트리거한다. room/course/session 도메인이 생기면
// 삭제하고 그쪽 "게임 시작" 흐름이 NinjaGameService.startSession()을 직접 호출하면 된다.
@Slf4j
@RestController
@RequestMapping("/api/dev/ninja")
public class DevNinjaSeedController {

    private static final int DEFAULT_TOTAL_ROUNDS = 5;

    private final NinjaGameService ninjaGameService;

    public DevNinjaSeedController(NinjaGameService ninjaGameService) {
        this.ninjaGameService = ninjaGameService;
    }

    @PostMapping("/seed")
    public ApiResponse<Void> seed(@RequestBody DevSeedRequest request) {
        int totalRounds = request.totalRounds() == null ? DEFAULT_TOTAL_ROUNDS : request.totalRounds();
        Set<String> participantTokens = new LinkedHashSet<>(request.participantTokens());
        log.info("[Controller] POST dev/ninja/seed : participantTokens={} totalRounds={}", participantTokens, totalRounds);

        ninjaGameService.startSession(DevRoomRepository.TEST_ROOM_ID, participantTokens, totalRounds);

        return ApiResponse.ok(null);
    }

    @PostMapping("/reset")
    public ApiResponse<Void> reset() {
        log.info("[Controller] POST dev/ninja/reset");
        ninjaGameService.resetSession(DevRoomRepository.TEST_ROOM_ID);
        return ApiResponse.ok(null);
    }

    record DevSeedRequest(List<String> participantTokens, Integer totalRounds) {
    }
}
