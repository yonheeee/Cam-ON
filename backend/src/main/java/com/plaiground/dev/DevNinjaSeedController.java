package com.plaiground.dev;

import com.plaiground.domain.game.ninja.service.NinjaGameService;
import com.plaiground.global.apiresponse.ApiResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TEMP — 방장이 대기방에서 코스를 확정하고 게임을 시작하면 자동으로 열려야 할 세션을,
// 그 흐름이 없는 지금은 이 엔드포인트로 수동 트리거한다. room 도메인은 이제 실제로 있어서
// (RedisRoomRepository) roomId를 요청에서 받아 그 방을 그대로 쓴다 — 방/코스/세션이 붙었던
// 고정 테스트 방 개념은 없앴다. course/session 도메인의 "게임 시작" 흐름이 생기면 이 컨트롤러를
// 지우고 그쪽이 NinjaGameService.startSession()을 직접 호출하면 된다.
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
        log.info("[Controller] POST dev/ninja/seed : roomId={} participantTokens={} totalRounds={}",
            request.roomId(), participantTokens, totalRounds);

        ninjaGameService.startSession(request.roomId(), participantTokens, totalRounds);

        return ApiResponse.ok(null);
    }

    @PostMapping("/reset")
    public ApiResponse<Void> reset(@RequestBody DevResetRequest request) {
        log.info("[Controller] POST dev/ninja/reset : roomId={}", request.roomId());
        ninjaGameService.resetSession(request.roomId());
        return ApiResponse.ok(null);
    }

    record DevSeedRequest(UUID roomId, List<String> participantTokens, Integer totalRounds) {
    }

    record DevResetRequest(UUID roomId) {
    }
}
