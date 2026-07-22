package com.plaiground.domain.game.ninja.controller;

import com.plaiground.domain.game.ninja.dto.AttackRequest;
import com.plaiground.domain.game.ninja.dto.AttackResponse;
import com.plaiground.domain.game.ninja.dto.NinjaStateResponse;
import com.plaiground.domain.game.ninja.dto.RoundSkillResponse;
import com.plaiground.domain.game.ninja.dto.TargetRequest;
import com.plaiground.domain.game.ninja.dto.TargetResponse;
import com.plaiground.domain.game.ninja.service.NinjaGameService;
import com.plaiground.global.apiresponse.ApiResponse;
import com.plaiground.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// {gameId}는 room의 UUID(roomId)를 그대로 받는다 — 이 방에서 지금 진행 중인 세션은 서비스가
// room.currentSessionSeq()로 알아서 찾는다(별도 세션 식별자 없음). 여기는 요청을 받아 서비스에
// 위임만 하고, Redis/WS 처리는 전부 NinjaGameService에 있다.
@RestController
@RequestMapping("/api/games/{gameId}/ninja")
public class NinjaController {

    private final NinjaGameService ninjaGameService;

    public NinjaController(NinjaGameService ninjaGameService) {
        this.ninjaGameService = ninjaGameService;
    }

    @GetMapping("/rounds/{round}/skill")
    public ApiResponse<RoundSkillResponse> getRoundSkill(
        @PathVariable UUID gameId,
        @PathVariable int round
    ) {
        return ApiResponse.ok(ninjaGameService.getRoundSkill(gameId, round));
    }

    @PostMapping("/rounds/{round}/attack")
    public ApiResponse<AttackResponse> attack(
        @PathVariable UUID gameId,
        @PathVariable int round,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody AttackRequest request
    ) {
        String participantToken = principal.participantId().toString();
        return ApiResponse.ok(ninjaGameService.attack(gameId, round, participantToken, request));
    }

    @PostMapping("/rounds/{round}/target")
    public ApiResponse<TargetResponse> target(
        @PathVariable UUID gameId,
        @PathVariable int round,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody TargetRequest request
    ) {
        String participantToken = principal.participantId().toString();
        return ApiResponse.ok(ninjaGameService.target(gameId, round, participantToken, request));
    }

    @GetMapping("/state")
    public ApiResponse<NinjaStateResponse> getState(@PathVariable UUID gameId) {
        return ApiResponse.ok(ninjaGameService.getState(gameId));
    }
}
