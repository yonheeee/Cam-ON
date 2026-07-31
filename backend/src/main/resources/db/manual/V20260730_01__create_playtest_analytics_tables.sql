-- Flyway가 아직 없는 프로젝트이므로 배포 DB에 한 번 수동 적용한다.
CREATE TABLE IF NOT EXISTS playtest_sessions (
    test_session_id BINARY(16) NOT NULL,
    room_key CHAR(64) NOT NULL,
    attempt_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    app_version VARCHAR(50) NOT NULL,
    experiment_version VARCHAR(100) NULL,
    initial_player_count INT NULL,
    completed_player_count INT NULL,
    course_completed BOOLEAN NOT NULL DEFAULT FALSE,
    total_duration_seconds BIGINT NULL,
    PRIMARY KEY (test_session_id),
    CONSTRAINT uk_playtest_sessions_room_attempt
        UNIQUE (room_key, attempt_number),
    INDEX idx_playtest_sessions_created_at (created_at),
    INDEX idx_playtest_sessions_experiment (experiment_version)
);

CREATE TABLE IF NOT EXISTS playtest_events (
    event_id BINARY(16) NOT NULL,
    test_session_id BINARY(16) NOT NULL,
    participant_key CHAR(64) NULL,
    event_name VARCHAR(50) NOT NULL,
    game_type VARCHAR(30) NULL,
    session_seq INT NULL,
    round_number INT NULL,
    occurred_at DATETIME(6) NOT NULL,
    server_received_at DATETIME(6) NOT NULL,
    properties_json JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_playtest_events_session
        FOREIGN KEY (test_session_id)
        REFERENCES playtest_sessions (test_session_id),
    INDEX idx_playtest_events_session_time (test_session_id, occurred_at),
    INDEX idx_playtest_events_name_time (event_name, occurred_at),
    INDEX idx_playtest_events_game (game_type, session_seq, round_number)
);
