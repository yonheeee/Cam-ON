package com.camon.domain.session.controller;

import com.camon.domain.session.dto.CreateSessionRequest;
import com.camon.domain.session.dto.CreateSessionResponse;
import com.camon.domain.session.service.SessionService;
import com.camon.global.apiresponse.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateSessionResponse>> createSession(
        @Valid @RequestBody CreateSessionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(sessionService.createSession(request)));
    }
}
