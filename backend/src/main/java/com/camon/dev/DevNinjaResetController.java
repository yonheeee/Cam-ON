package com.camon.dev;

import com.camon.domain.game.ninja.service.NinjaGameService;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.apiresponse.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TEMP — 테스트 중 진행 중인 게임을 통째로 지우고 방을 다시 대기 상태로 되돌리는 개발용 엔드포인트.
// 정식 게임 시작 흐름(GameStartService, POST /api/rooms/{roomId}/start)이 생기면서 수동 세션
// 트리거였던 seed 엔드포인트는 제거됐고, 이 reset만 남았다. course/session 도메인이 완성되면
// 이것도 함께 제거한다.
@Slf4j
@Profile("local")
@RestController
@RequestMapping("/api/dev/ninja")
public class DevNinjaResetController {

    private final NinjaGameService ninjaGameService;
    private final RoomRepository roomRepository;

    public DevNinjaResetController(
        NinjaGameService ninjaGameService,
        RoomRepository roomRepository
    ) {
        this.ninjaGameService = ninjaGameService;
        this.roomRepository = roomRepository;
    }

    @PostMapping("/reset")
    public ApiResponse<Void> reset(@Valid @RequestBody DevResetRequest request) {
        log.info("[Controller] POST dev/ninja/reset : roomId={}", request.roomId());
        ninjaGameService.resetSession(request.roomId());
        // 정식 게임 시작 흐름이 방을 PLAYING으로 올리므로, 리셋 시 방을 다시 WAITING으로 되돌려야
        // 대기방에서 준비/재시작이 가능하다(안 그러면 status 가드에 막힌다).
        roomRepository.updateStatus(request.roomId(), RoomStatus.WAITING);
        return ApiResponse.ok(null);
    }

    record DevResetRequest(@NotNull UUID roomId) {
    }
}
