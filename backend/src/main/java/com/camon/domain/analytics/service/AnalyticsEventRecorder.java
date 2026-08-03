package com.camon.domain.analytics.service;

import com.camon.domain.analytics.config.AnalyticsProperties;
import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import com.camon.domain.analytics.domain.AnalyticsEventName;
import com.camon.domain.analytics.domain.PlaytestEvent;
import com.camon.domain.analytics.domain.PlaytestSession;
import com.camon.domain.analytics.repository.PlaytestEventRepository;
import com.camon.domain.analytics.repository.PlaytestSessionRepository;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsEventRecorder {

    private final PlaytestEventRepository eventRepository;
    private final PlaytestSessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final AnalyticsKeyHasher keyHasher;
    private final AnalyticsExitContextResolver exitContextResolver;
    private final AnalyticsProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnalyticsEventRecorder(
        PlaytestEventRepository eventRepository,
        PlaytestSessionRepository sessionRepository,
        ParticipantRepository participantRepository,
        AnalyticsKeyHasher keyHasher,
        AnalyticsExitContextResolver exitContextResolver,
        AnalyticsProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.keyHasher = keyHasher;
        this.exitContextResolver = exitContextResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void validateRoomMembership(UUID roomId, UUID participantId) {
        if (participantRepository.findById(roomId, participantId).isEmpty()) {
            throw new BusinessException(ErrorCode.ROOM_ACCESS_DENIED);
        }
    }

    @Transactional
    public void record(AnalyticsDomainEvent event) {
        if (eventRepository.existsById(event.eventId())) {
            return;
        }

        PlaytestSession session = resolveSession(event);
        session.updateClientVersions(event.appVersion(), event.experimentVersion());
        updateSessionSummary(session, event);
        sessionRepository.save(session);

        String participantKey = event.participantId() == null
            ? null
            : keyHasher.hash(event.participantId());
        String analyticsUserKey = event.analyticsUserId() == null
            ? null
            : keyHasher.hash(event.analyticsUserId());
        PlaytestEvent savedEvent = new PlaytestEvent(
            event.eventId(),
            session.getTestSessionId(),
            participantKey,
            analyticsUserKey,
            event.eventName().name(),
            event.gameType(),
            event.sessionSeq(),
            event.roundNumber(),
            event.occurredAt(),
            clock.instant(),
            serializeProperties(enrichExitProperties(
                event,
                session.getTestSessionId(),
                participantKey
            ))
        );
        try {
            eventRepository.save(savedEvent);
        } catch (DataIntegrityViolationException duplicate) {
            if (!eventRepository.existsById(event.eventId())) {
                throw duplicate;
            }
        }
    }

    private Map<String, Object> enrichExitProperties(
        AnalyticsDomainEvent event,
        UUID testSessionId,
        String participantKey
    ) {
        if (event.eventName() != AnalyticsEventName.PARTICIPANT_DISCONNECTED
            && event.eventName() != AnalyticsEventName.PARTICIPANT_LEFT) {
            return event.properties();
        }
        LinkedHashMap<String, Object> enriched =
            new LinkedHashMap<>(event.properties());
        Map<String, Object> current = exitContextResolver.resolve(event.roomId());
        current.forEach(enriched::putIfAbsent);
        if (event.eventName() == AnalyticsEventName.PARTICIPANT_LEFT
            && !enriched.containsKey("roomStatus")
            && participantKey != null) {
            previousDisconnectProperties(testSessionId, participantKey)
                .forEach(enriched::putIfAbsent);
        }
        return Map.copyOf(enriched);
    }

    private Map<String, Object> previousDisconnectProperties(
        UUID testSessionId,
        String participantKey
    ) {
        return eventRepository
            .findFirstByTestSessionIdAndParticipantKeyAndEventNameOrderByOccurredAtDesc(
                testSessionId,
                participantKey,
                AnalyticsEventName.PARTICIPANT_DISCONNECTED.name()
            )
            .map(PlaytestEvent::getPropertiesJson)
            .map(this::deserializeProperties)
            .orElse(Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeProperties(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private PlaytestSession resolveSession(AnalyticsDomainEvent event) {
        String roomKey = keyHasher.hash(event.roomId());
        PlaytestSession latest = sessionRepository
            .findFirstByRoomKeyOrderByAttemptNumberDesc(roomKey)
            .orElse(null);
        if (latest == null) {
            return newSession(event, roomKey, 1);
        }
        if (latest.isCourseCompleted() && opensNewAttempt(event.eventName())) {
            return newSession(event, roomKey, latest.getAttemptNumber() + 1);
        }
        return latest;
    }

    private PlaytestSession newSession(
        AnalyticsDomainEvent event,
        String roomKey,
        int attemptNumber
    ) {
        return new PlaytestSession(
            UUID.randomUUID(),
            roomKey,
            attemptNumber,
            event.occurredAt(),
            defaultAppVersion(event.appVersion()),
            defaultExperimentVersion(event.experimentVersion())
        );
    }

    private boolean opensNewAttempt(AnalyticsEventName eventName) {
        return eventName == AnalyticsEventName.READY_CHANGED
            || eventName == AnalyticsEventName.GAME_SESSION_STARTED
            || eventName == AnalyticsEventName.COURSE_STARTED;
    }

    private void updateSessionSummary(
        PlaytestSession session,
        AnalyticsDomainEvent event
    ) {
        int playerCount = numberProperty(event.properties(), "playerCount");
        if (event.eventName() == AnalyticsEventName.COURSE_STARTED) {
            session.start(event.occurredAt(), playerCount);
        } else if (event.eventName() == AnalyticsEventName.COURSE_FINISHED) {
            session.finish(event.occurredAt(), playerCount);
        }
    }

    private int numberProperty(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String serializeProperties(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Analytics properties are not serializable", exception);
        }
    }

    private String defaultAppVersion(String requested) {
        return requested == null || requested.isBlank()
            ? properties.appVersion()
            : requested;
    }

    private String defaultExperimentVersion(String requested) {
        return requested == null || requested.isBlank()
            ? properties.experimentVersion()
            : requested;
    }
}
