package com.camon.domain.analytics.controller;

import com.camon.domain.analytics.dto.RecordClientEventsRequest;
import com.camon.domain.analytics.service.ClientAnalyticsService;
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
@RequestMapping("/api/rooms/{roomId}/analytics")
public class AnalyticsController {

    private final ClientAnalyticsService analyticsService;

    public AnalyticsController(ClientAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> recordEvents(
        @PathVariable UUID roomId,
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody RecordClientEventsRequest request
    ) {
        analyticsService.record(roomId, principal.participantId(), request);
        return ResponseEntity.accepted().build();
    }
}
