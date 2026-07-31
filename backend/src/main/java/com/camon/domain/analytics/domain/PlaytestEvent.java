package com.camon.domain.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "playtest_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_playtest_events_event_id",
        columnNames = "event_id"
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PlaytestEvent {

    @Id
    @Column(name = "event_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID eventId;

    @Column(name = "test_session_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID testSessionId;

    @Column(name = "participant_key", length = 64)
    private String participantKey;

    @Column(name = "analytics_user_key", length = 64)
    private String analyticsUserKey;

    @Column(name = "event_name", nullable = false, length = 50)
    private String eventName;

    @Column(name = "game_type", length = 30)
    private String gameType;

    @Column(name = "session_seq")
    private Integer sessionSeq;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    @Column(name = "properties_json", nullable = false, columnDefinition = "TEXT")
    private String propertiesJson;
}
