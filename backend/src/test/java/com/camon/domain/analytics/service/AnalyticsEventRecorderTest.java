package com.camon.domain.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.analytics.config.AnalyticsProperties;
import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.domain.AnalyticsEventName;
import com.camon.domain.analytics.domain.PlaytestEvent;
import com.camon.domain.analytics.domain.PlaytestSession;
import com.camon.domain.analytics.repository.PlaytestEventRepository;
import com.camon.domain.analytics.repository.PlaytestSessionRepository;
import com.camon.domain.room.repository.ParticipantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnalyticsEventRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    private PlaytestEventRepository eventRepository;
    private PlaytestSessionRepository sessionRepository;
    private ParticipantRepository participantRepository;
    private AnalyticsExitContextResolver exitContextResolver;
    private AnalyticsEventRecorder recorder;

    @BeforeEach
    void setUp() {
        eventRepository = org.mockito.Mockito.mock(PlaytestEventRepository.class);
        sessionRepository = org.mockito.Mockito.mock(PlaytestSessionRepository.class);
        participantRepository = org.mockito.Mockito.mock(ParticipantRepository.class);
        exitContextResolver = org.mockito.Mockito.mock(AnalyticsExitContextResolver.class);
        AnalyticsProperties properties = new AnalyticsProperties(
            "test-secret",
            "test-app",
            "baseline"
        );
        recorder = new AnalyticsEventRecorder(
            eventRepository,
            sessionRepository,
            participantRepository,
            new AnalyticsKeyHasher(properties),
            exitContextResolver,
            properties,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void enrichesDisconnectWithCurrentGamePosition() {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(eventRepository.existsById(any())).thenReturn(false);
        when(sessionRepository.findFirstByRoomKeyOrderByAttemptNumberDesc(any()))
            .thenReturn(Optional.empty());
        when(exitContextResolver.resolve(roomId)).thenReturn(Map.of(
            "roomStatus", "PLAYING",
            "gameType", "NINJA",
            "sessionSeq", 2,
            "roundNumber", 1,
            "exchangeNumber", 4,
            "phase", "ROUND"
        ));

        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.PARTICIPANT_DISCONNECTED,
            roomId,
            participantId,
            NOW,
            Map.of()
        ));

        ArgumentCaptor<PlaytestEvent> captor =
            ArgumentCaptor.forClass(PlaytestEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getPropertiesJson())
            .contains("\"roomStatus\":\"PLAYING\"")
            .contains("\"gameType\":\"NINJA\"")
            .contains("\"roundNumber\":1")
            .contains("\"exchangeNumber\":4");
    }

    @Test
    void storesAnonymizedEventAndCreatesSession() {
        UUID eventId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID analyticsUserId = UUID.randomUUID();
        when(eventRepository.existsById(eventId)).thenReturn(false);
        when(sessionRepository.findFirstByRoomKeyOrderByAttemptNumberDesc(any()))
            .thenReturn(Optional.empty());

        recorder.record(new AnalyticsDomainEvent(
            eventId,
            AnalyticsEventName.CAMERA_PERMISSION_RESULT,
            roomId,
            participantId,
            null,
            null,
            null,
            NOW.minusSeconds(1),
            "frontend-1",
            "camera-v2",
            analyticsUserId,
            Map.of("result", "GRANTED")
        ));

        ArgumentCaptor<PlaytestSession> sessionCaptor =
            ArgumentCaptor.forClass(PlaytestSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getAppVersion()).isEqualTo("frontend-1");
        assertThat(sessionCaptor.getValue().getRoomKey()).hasSize(64);

        ArgumentCaptor<PlaytestEvent> eventCaptor =
            ArgumentCaptor.forClass(PlaytestEvent.class);
        verify(eventRepository).save(eventCaptor.capture());
        PlaytestEvent saved = eventCaptor.getValue();
        assertThat(saved.getParticipantKey()).hasSize(64);
        assertThat(saved.getParticipantKey()).doesNotContain(participantId.toString());
        assertThat(saved.getAnalyticsUserKey()).hasSize(64);
        assertThat(saved.getAnalyticsUserKey())
            .doesNotContain(analyticsUserId.toString());
        assertThat(saved.getPropertiesJson()).contains("GRANTED");
        assertThat(saved.getServerReceivedAt()).isEqualTo(NOW);
    }

    @Test
    void ignoresDuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.existsById(eventId)).thenReturn(true);

        recorder.record(new AnalyticsDomainEvent(
            eventId,
            AnalyticsEventName.READY_CHANGED,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            null,
            NOW,
            null,
            null,
            null,
            Map.of("ready", true)
        ));

        verify(sessionRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void marksSessionCompletedAndCalculatesDuration() {
        UUID roomId = UUID.randomUUID();
        PlaytestSession session = new PlaytestSession(
            UUID.randomUUID(),
            "a".repeat(64),
            1,
            NOW.minusSeconds(30),
            "test",
            "baseline"
        );
        session.start(NOW.minusSeconds(20), 4);
        when(eventRepository.existsById(any())).thenReturn(false);
        when(sessionRepository.findFirstByRoomKeyOrderByAttemptNumberDesc(any()))
            .thenReturn(Optional.of(session));

        recorder.record(AnalyticsDomainEvent.serverIdempotent(
            AnalyticsEventName.COURSE_FINISHED,
            roomId,
            null,
            "course",
            NOW,
            Map.of("playerCount", 3)
        ));

        assertThat(session.isCourseCompleted()).isTrue();
        assertThat(session.getInitialPlayerCount()).isEqualTo(4);
        assertThat(session.getCompletedPlayerCount()).isEqualTo(3);
        assertThat(session.getTotalDurationSeconds()).isEqualTo(20);
    }
}
