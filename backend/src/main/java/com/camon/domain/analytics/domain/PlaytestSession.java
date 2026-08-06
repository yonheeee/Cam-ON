package com.camon.domain.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
// 2차 유저테스트부터는 playtest2_* 에 기록한다. 1차 테이블(playtest_sessions/_events)은
// 그 시점에서 동결되고, 회차를 나눈 이유와 1차 뷰가 그대로 남는 이유는
// db/manual/V20260806_03__create_playtest2_tables.sql에 적어 뒀다.
//
// 유니크 제약과 인덱스도 여기 선언한다 — 배포는 ddl-auto: update라 JPA가 테이블을 먼저 만들고,
// 그러면 마이그레이션 SQL이 no-op이 되어 제약만 빠진 테이블이 남는다(PlaytestEvent 주석 참고).
// room_key + attempt_number 유니크는 특히 중요하다: 같은 방을 다시 플레이할 때 회차가 겹쳐
// 들어가는 걸 막는 유일한 방어선이라, 빠지면 집계가 조용히 두 배로 부풀 수 있다.
@Table(
    name = "playtest2_sessions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_playtest2_sessions_room_attempt",
        columnNames = {"room_key", "attempt_number"}
    ),
    indexes = {
        @Index(
            name = "idx_playtest2_sessions_created_at",
            columnList = "created_at"
        ),
        @Index(
            name = "idx_playtest2_sessions_experiment",
            columnList = "experiment_version"
        )
    }
)
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
