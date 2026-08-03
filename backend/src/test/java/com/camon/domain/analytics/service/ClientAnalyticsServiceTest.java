package com.camon.domain.analytics.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.dto.ClientAnalyticsEventRequest;
import com.camon.domain.analytics.dto.ClientAnalyticsEventType;
import com.camon.domain.analytics.dto.RecordClientEventsRequest;
import com.camon.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ClientAnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    private AnalyticsEventRecorder recorder;
    private ApplicationEventPublisher eventPublisher;
    private ClientAnalyticsService service;

    @BeforeEach
    void setUp() {
        recorder = org.mockito.Mockito.mock(AnalyticsEventRecorder.class);
        eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        service = new ClientAnalyticsService(
            recorder,
            eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void verifiesMembershipAndPublishesAllowlistedClientEvent() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID analyticsUserId = UUID.randomUUID();
        RecordClientEventsRequest request = new RecordClientEventsRequest(
            analyticsUserId,
            "frontend-1",
            "guide-v2",
            List.of(new ClientAnalyticsEventRequest(
                eventId,
                ClientAnalyticsEventType.NINJA_RECOGNITION_WINDOW,
                NOW.minusSeconds(1),
                "NINJA",
                1,
                2,
                Map.of("attemptCount", 18, "validCount", 11)
            ))
        );

        service.record(roomId, participantId, request);

        verify(recorder).validateRoomMembership(roomId, participantId);
        ArgumentCaptor<AnalyticsDomainEvent> captor =
            ArgumentCaptor.forClass(AnalyticsDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().eventId())
            .isEqualTo(eventId);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().participantId())
            .isEqualTo(participantId);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().experimentVersion())
            .isEqualTo("guide-v2");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().analyticsUserId())
            .isEqualTo(analyticsUserId);
    }

    @Test
    void doesNotPublishWhenParticipantIsNotInRoom() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new BusinessException(
            com.camon.global.exception.ErrorCode.ROOM_ACCESS_DENIED
        )).when(recorder).validateRoomMembership(roomId, participantId);

        RecordClientEventsRequest request = new RecordClientEventsRequest(
            UUID.randomUUID(),
            "frontend-1",
            "baseline",
            List.of(new ClientAnalyticsEventRequest(
                UUID.randomUUID(),
                ClientAnalyticsEventType.RESULT_SCREEN_VIEWED,
                NOW,
                null,
                null,
                null,
                Map.of()
            ))
        );

        assertThatThrownBy(() -> service.record(roomId, participantId, request))
            .isInstanceOf(BusinessException.class);
        verify(eventPublisher, never()).publishEvent(
            org.mockito.ArgumentMatchers.any()
        );
    }
}
