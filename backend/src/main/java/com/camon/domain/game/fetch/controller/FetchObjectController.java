package com.camon.domain.game.fetch.controller;

import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.service.FetchObjectGameFacade;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{gameId}/fetch-object")
public class FetchObjectController {

    private final FetchObjectGameFacade fetchObjectGameFacade;

    public FetchObjectController(FetchObjectGameFacade fetchObjectGameFacade) {
        this.fetchObjectGameFacade = fetchObjectGameFacade;
    }

    @PostMapping("/submissions")
    public ApiResponse<FetchSubmissionResponse> submit(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody FetchSubmissionRequest request
    ) {
        return ApiResponse.ok(
            fetchObjectGameFacade.submit(
                gameId,
                principal.participantId(),
                request
            )
        );
    }
}
