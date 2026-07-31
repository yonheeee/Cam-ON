package com.camon.domain.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "playtest_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaytestSession {

    @Id
    @Column(name = "test_session_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID testSessionId;

    @Column(name = "room_key", nullable = false, length = 64)
    private String roomKey;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "app_version", nullable = false, length = 50)
    private String appVersion;

    @Column(name = "experiment_version", length = 100)
    private String experimentVersion;

    @Column(name = "initial_player_count")
    private Integer initialPlayerCount;

    @Column(name = "completed_player_count")
    private Integer completedPlayerCount;

    @Column(name = "course_completed", nullable = false)
    private boolean courseCompleted;

    @Column(name = "total_duration_seconds")
    private Long totalDurationSeconds;

    public PlaytestSession(
        UUID testSessionId,
        String roomKey,
        int attemptNumber,
        Instant createdAt,
        String appVersion,
        String experimentVersion
    ) {
        this.testSessionId = testSessionId;
        this.roomKey = roomKey;
        this.attemptNumber = attemptNumber;
        this.createdAt = createdAt;
        this.appVersion = appVersion;
        this.experimentVersion = experimentVersion;
    }

    public void start(Instant startedAt, int playerCount) {
        if (this.startedAt == null) {
            this.startedAt = startedAt;
        }
        this.initialPlayerCount = playerCount;
    }

    public void finish(Instant finishedAt, int playerCount) {
        this.finishedAt = finishedAt;
        this.completedPlayerCount = playerCount;
        this.courseCompleted = true;
        Instant base = startedAt == null ? createdAt : startedAt;
        this.totalDurationSeconds = Math.max(
            0,
            java.time.Duration.between(base, finishedAt).toSeconds()
        );
    }

    public void updateClientVersions(String appVersion, String experimentVersion) {
        if (appVersion != null && !appVersion.isBlank()) {
            this.appVersion = appVersion;
        }
        if (experimentVersion != null && !experimentVersion.isBlank()) {
            this.experimentVersion = experimentVersion;
        }
    }
}
