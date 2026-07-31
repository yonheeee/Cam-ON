package com.camon.domain.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.camon.domain.analytics.config.AnalyticsProperties;
import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.domain.AnalyticsEventName;
import com.camon.domain.analytics.service.AnalyticsEventRecorder;
import com.camon.domain.analytics.service.AnalyticsKeyHasher;
import com.camon.domain.room.repository.ParticipantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class AnalyticsPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    @Autowired
    private PlaytestEventRepository eventRepository;
    @Autowired
    private PlaytestSessionRepository sessionRepository;

    private AnalyticsEventRecorder recorder;

    @BeforeEach
    void setUp() {
        AnalyticsProperties properties = new AnalyticsProperties(
            "integration-test-secret",
            "test-app",
            "baseline"
        );
        recorder = new AnalyticsEventRecorder(
            eventRepository,
            sessionRepository,
            org.mockito.Mockito.mock(ParticipantRepository.class),
            new AnalyticsKeyHasher(properties),
            org.mockito.Mockito.mock(
                com.camon.domain.analytics.service.AnalyticsExitContextResolver.class
            ),
            properties,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void persistsChronologicalPlayLogAfterCourseCompletion() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        recorder.record(AnalyticsDomainEvent.serverIdempotent(
            AnalyticsEventName.ROOM_CREATED,
            roomId,
            hostId,
            "created",
            NOW.minusSeconds(120),
            Map.of("maxPlayers", 4)
        ));
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.COURSE_STARTED,
            roomId,
            hostId,
            NOW.minusSeconds(100),
            Map.of("playerCount", 4, "courseSize", 2)
        ));
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.COURSE_FINISHED,
            roomId,
            null,
            NOW,
            Map.of("playerCount", 3, "totalSessions", 2)
        ));

        var session = sessionRepository
            .findFirstByRoomKeyOrderByAttemptNumberDesc(
                new AnalyticsKeyHasher(new AnalyticsProperties(
                    "integration-test-secret",
                    "test-app",
                    "baseline"
                )).hash(roomId)
            )
            .orElseThrow();
        assertThat(session.isCourseCompleted()).isTrue();
        assertThat(session.getInitialPlayerCount()).isEqualTo(4);
        assertThat(session.getCompletedPlayerCount()).isEqualTo(3);
        assertThat(session.getTotalDurationSeconds()).isEqualTo(100);
        assertThat(eventRepository.findAll())
            .extracting(event -> event.getEventName())
            .containsExactlyInAnyOrder(
                "ROOM_CREATED",
                "COURSE_STARTED",
                "COURSE_FINISHED"
            );
    }

    @Test
    void createsSeparateSummaryForReplayInSameRoom() {
        UUID roomId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        recordCourse(roomId, hostId, NOW.minusSeconds(200), 3, 2);
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.RESULT_SCREEN_VIEWED,
            roomId,
            hostId,
            NOW.minusSeconds(90),
            Map.of("totalSessions", 2)
        ));
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.READY_CHANGED,
            roomId,
            hostId,
            NOW.minusSeconds(80),
            Map.of("ready", true, "allReady", true)
        ));
        recordCourse(roomId, hostId, NOW.minusSeconds(60), 2, 1);

        String roomKey = new AnalyticsKeyHasher(new AnalyticsProperties(
            "integration-test-secret",
            "test-app",
            "baseline"
        )).hash(roomId);
        var sessions = sessionRepository.findAll().stream()
            .filter(session -> session.getRoomKey().equals(roomKey))
            .sorted(java.util.Comparator.comparingInt(
                com.camon.domain.analytics.domain.PlaytestSession::getAttemptNumber
            ))
            .toList();

        assertThat(sessions).hasSize(2);
        assertThat(sessions).extracting(
            com.camon.domain.analytics.domain.PlaytestSession::getAttemptNumber
        ).containsExactly(1, 2);
        assertThat(sessions).extracting(
            com.camon.domain.analytics.domain.PlaytestSession::getInitialPlayerCount
        ).containsExactly(3, 2);
        assertThat(sessions).extracting(
            com.camon.domain.analytics.domain.PlaytestSession::getCompletedPlayerCount
        ).containsExactly(3, 2);
        assertThat(eventRepository.findAll()).hasSize(8);
    }

    private void recordCourse(
        UUID roomId,
        UUID hostId,
        Instant startedAt,
        int playerCount,
        int totalSessions
    ) {
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.COURSE_STARTED,
            roomId,
            hostId,
            startedAt,
            Map.of("playerCount", playerCount, "courseSize", totalSessions)
        ));
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.GAME_SESSION_STARTED,
            roomId,
            null,
            startedAt.plusSeconds(1),
            Map.of("playerCount", playerCount)
        ));
        recorder.record(AnalyticsDomainEvent.server(
            AnalyticsEventName.COURSE_FINISHED,
            roomId,
            null,
            startedAt.plusSeconds(50),
            Map.of("playerCount", playerCount, "totalSessions", totalSessions)
        ));
    }
}
