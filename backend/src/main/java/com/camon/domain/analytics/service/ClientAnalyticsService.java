package com.camon.domain.analytics.service;

import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.dto.ClientAnalyticsEventRequest;
import com.camon.domain.analytics.dto.RecordClientEventsRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ClientAnalyticsService {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofDays(1);

    private final AnalyticsEventRecorder recorder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ClientAnalyticsService(
        AnalyticsEventRecorder recorder,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.recorder = recorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public void record(
        UUID roomId,
        UUID participantId,
        RecordClientEventsRequest request
    ) {
        recorder.validateRoomMembership(roomId, participantId);
        for (ClientAnalyticsEventRequest clientEvent : request.events()) {
            eventPublisher.publishEvent(new AnalyticsDomainEvent(
                clientEvent.eventId(),
                clientEvent.eventName().toEventName(),
                roomId,
                participantId,
                clientEvent.gameType(),
                positiveOrNull(clientEvent.sessionSeq()),
                positiveOrNull(clientEvent.roundNumber()),
                safeOccurredAt(clientEvent.occurredAt()),
                request.appVersion(),
                request.experimentVersion(),
                request.analyticsUserId(),
                clientEvent.properties() == null
                    ? Map.of()
                    : clientEvent.properties()
            ));
        }
    }

    private java.time.Instant safeOccurredAt(java.time.Instant requested) {
        java.time.Instant now = clock.instant();
        if (requested.isBefore(now.minus(MAX_CLOCK_SKEW))
            || requested.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return now;
        }
        return requested;
    }

    private Integer positiveOrNull(Integer value) {
        return value == null || value > 0 ? value : null;
    }
}
