package com.camon.domain.game.fetch.controller;

import com.camon.domain.game.fetch.dto.FetchObjectStateResponse;
import com.camon.domain.game.fetch.dto.FetchSkipVoteResponse;
import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.service.FetchObjectGameFacade;
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
@RequestMapping("/api/games/{gameId}/fetch-object")
public class FetchObjectController {

    private final FetchObjectGameFacade fetchObjectGameFacade;

    public FetchObjectController(FetchObjectGameFacade fetchObjectGameFacade) {
        this.fetchObjectGameFacade = fetchObjectGameFacade;
    }

    @GetMapping("/state")
    public ApiResponse<FetchObjectStateResponse> getState(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        return ApiResponse.ok(
            fetchObjectGameFacade.getState(
                gameId,
                principal.participantId()
            )
        );
    }

    // 스킵 투표 — 첫 성공 전, 접속 참가자 전원 투표 시 라운드 조기 종료.
    // 중복 투표는 멱등이라 별도 바디 없이 참가자 인증만으로 충분하다.
    @PostMapping("/skip-votes")
    public ApiResponse<FetchSkipVoteResponse> voteSkip(
        @PathVariable Long gameId,
        @AuthenticationPrincipal GuestPrincipal principal
    ) {
        return ApiResponse.ok(
            fetchObjectGameFacade.voteSkip(
                gameId,
                principal.participantId()
            )
        );
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
