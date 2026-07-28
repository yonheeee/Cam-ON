package com.camon.domain.game.ninja.controller;

import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.dto.AttackResponse;
import com.camon.domain.game.ninja.dto.NinjaStateResponse;
import com.camon.domain.game.ninja.dto.RoundSkillResponse;
import com.camon.domain.game.ninja.dto.TargetRequest;
import com.camon.domain.game.ninja.dto.TargetResponse;
import com.camon.domain.game.ninja.service.NinjaGameFacade;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/api/games/{gameId}/ninja")
public class NinjaController {

    private final NinjaGameFacade ninjaGameFacade;

    public NinjaController(NinjaGameFacade ninjaGameFacade) {
        this.ninjaGameFacade = ninjaGameFacade;
    }

    @GetMapping("/rounds/{round}/skill")
    public ApiResponse<RoundSkillResponse> getRoundSkill(
        @PathVariable Long gameId,
        @PathVariable int round,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        log.info("[Controller] GET rounds/{}/skill : gameId={}", round, gameId);
        RoundSkillResponse response = ninjaGameFacade.getRoundSkill(
            gameId,
            round,
            principal.participantId()
        );
        log.info("[Controller] GET rounds/{}/skill 완료 : skillId={} skillName={}", round, response.skillId(), response.skillName());
        return ApiResponse.ok(response);
    }

    @PostMapping("/rounds/{round}/attack")
    public ApiResponse<AttackResponse> attack(
        @PathVariable Long gameId,
        @PathVariable int round,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody AttackRequest request
    ) {
        String participantToken = principal.participantId().toString();
        log.info("[Controller] POST rounds/{}/attack : gameId={} participantToken={} skillId={}",
            round, gameId, participantToken, request.skillId());
        AttackResponse response = ninjaGameFacade.attack(
            gameId,
            round,
            principal.participantId(),
            request
        );
        log.info("[Controller] POST rounds/{}/attack 완료 : attackerToken={} 공격권 선점 성공", round, response.attackerToken());
        return ApiResponse.ok(response);
    }

    @PostMapping("/rounds/{round}/target")
    public ApiResponse<TargetResponse> target(
        @PathVariable Long gameId,
        @PathVariable int round,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody TargetRequest request
    ) {
        String participantToken = principal.participantId().toString();
        log.info("[Controller] POST rounds/{}/target : gameId={} participantToken={} targetToken={}",
            round, gameId, participantToken, request.targetToken());
        TargetResponse response = ninjaGameFacade.target(
            gameId,
            round,
            principal.participantId(),
            request
        );
        log.info("[Controller] POST rounds/{}/target 완료 : damage={} targetHpAfter={} eliminated={} gameEnded={}",
            round, response.damage(), response.targetHpAfter(), response.targetEliminated(), response.gameEnded());
        return ApiResponse.ok(response);
    }

    @GetMapping("/state")
    public ApiResponse<NinjaStateResponse> getState(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        NinjaStateResponse response = ninjaGameFacade.getState(
            gameId,
            principal.participantId()
        );
        log.debug("[Controller] GET state : gameId={} round={}/{} alive={}",
            gameId, response.round(), response.totalRounds(), response.alivePlayers().size());
        return ApiResponse.ok(response);
    }
}
