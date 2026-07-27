package com.camon.domain.game.common.controller;

import com.camon.domain.game.common.dto.StartGameRequest;
import com.camon.domain.game.common.dto.StartGameResponse;
import com.camon.domain.game.common.service.GameStartService;
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

    private final GameStartService gameStartService;

    public GameStartController(GameStartService gameStartService) {
        this.gameStartService = gameStartService;
    }

    @PostMapping("/{roomId}/start")
    public ResponseEntity<ApiResponse<StartGameResponse>> start(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId,
        @Valid @RequestBody StartGameRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(gameStartService.start(
            roomId,
            principal.participantId(),
            request
        )));
    }
}
