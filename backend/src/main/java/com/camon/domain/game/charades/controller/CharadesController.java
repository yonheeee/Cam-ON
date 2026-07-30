package com.camon.domain.game.charades.controller;

import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.dto.CharadesGuessResponse;
import com.camon.domain.game.charades.dto.CharadesStateResponse;
import com.camon.domain.game.charades.dto.CharadesWordResponse;
import com.camon.domain.game.charades.service.CharadesGameFacade;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{gameId}/charades")
public class CharadesController {

    private final CharadesGameFacade charadesGameFacade;

    public CharadesController(CharadesGameFacade charadesGameFacade) {
        this.charadesGameFacade = charadesGameFacade;
    }

    @GetMapping("/state")
    public ApiResponse<CharadesStateResponse> getState(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        return ApiResponse.ok(
            charadesGameFacade.getState(
                gameId,
                principal.participantId()
            )
        );
    }

    @GetMapping("/word")
    public ApiResponse<CharadesWordResponse> getCurrentWord(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        return ApiResponse.ok(
            charadesGameFacade.getCurrentWord(
                gameId,
                principal.participantId()
            )
        );
    }

    @PostMapping("/guesses")
    public ApiResponse<CharadesGuessResponse> submitGuess(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody CharadesGuessRequest request
    ) {
        return ApiResponse.ok(
            charadesGameFacade.submitGuess(
                gameId,
                principal.participantId(),
                request
            )
        );
    }
}
