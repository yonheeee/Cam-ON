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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
// 2차 유저테스트부터는 playtest2_* 에 기록한다 (PlaytestSession의 주석 참고).
//
// 인덱스를 여기 선언해 두는 게 중요하다 — 배포 백엔드는 ddl-auto: update로 돌아서 테이블을
// JPA가 먼저 만들어 버리고, 그러면 마이그레이션 SQL의 CREATE TABLE IF NOT EXISTS가 통째로
// no-op이 돼 인덱스만 빠진 테이블이 남는다. 1차에서 실제로 그래서 apply-metric-views.sh가
// information_schema를 뒤져 인덱스를 덧붙이는 보정을 하고 있었다.
@Table(
    name = "playtest2_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_playtest2_events_event_id",
        columnNames = "event_id"
    ),
    indexes = {
        @Index(
            name = "idx_playtest2_events_session_time",
            columnList = "test_session_id, occurred_at"
        ),
        @Index(
            name = "idx_playtest2_events_name_time",
            columnList = "event_name, occurred_at"
        ),
        @Index(
            name = "idx_playtest2_events_game",
            columnList = "game_type, session_seq, round_number"
        ),
        @Index(
            name = "idx_playtest2_events_analytics_user_time",
            columnList = "analytics_user_key, occurred_at"
        )
    }
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
