-- 2차 유저테스트용 수집 테이블.
--
-- 1차(playtest_sessions / playtest_events)와 같은 테이블에 experiment_version만 다르게 쌓지
-- 않고 테이블을 갈라 놓는다. 1차 데이터에는 개발 중 자체 플레이와 analytics_user_key가
-- 끊긴 구간(V20260731_01 이전)이 섞여 있어서, 어떤 집계를 내든 "이 행이 진짜 유저테스트인가"를
-- 매번 조건으로 걸러야 한다. 2차는 그 조건 없이 테이블 전체가 곧 모집단이 되게 한다.
--
-- 1차 테이블은 그대로 둔다(삭제·이전 없음). 백엔드는 이 마이그레이션 이후 2차 테이블에만
-- 기록하므로 1차는 그 시점에서 동결된다.
--
-- 컬럼은 1차의 최종 형태(V20260730_01 + _02의 attempt_number + V20260731_01의
-- analytics_user_key)를 처음부터 합쳐 둔 것이다 -- 나중에 ALTER로 덧붙이면 1차에서 겪은
-- "컬럼은 있는데 인덱스는 없다" 같은 어긋남이 되풀이된다.
--
-- 배포 백엔드는 local 프로필(ddl-auto: update)로 돌아서 테이블 자체는 JPA가 만들어 준다.
-- 그래도 이 파일이 필요한 이유는 인덱스·유니크 제약이 엔티티에 선언돼 있지 않아 자동으로
-- 생기지 않기 때문이다(1차에서 apply-metric-views.sh가 그 보정을 하고 있었다).
CREATE TABLE IF NOT EXISTS playtest2_sessions (
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
    CONSTRAINT uk_playtest2_sessions_room_attempt
        UNIQUE (room_key, attempt_number),
    INDEX idx_playtest2_sessions_created_at (created_at),
    INDEX idx_playtest2_sessions_experiment (experiment_version)
);

CREATE TABLE IF NOT EXISTS playtest2_events (
    event_id BINARY(16) NOT NULL,
    test_session_id BINARY(16) NOT NULL,
    participant_key CHAR(64) NULL,
    analytics_user_key CHAR(64) NULL,
    event_name VARCHAR(50) NOT NULL,
    game_type VARCHAR(30) NULL,
    session_seq INT NULL,
    round_number INT NULL,
    occurred_at DATETIME(6) NOT NULL,
    server_received_at DATETIME(6) NOT NULL,
    properties_json JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_playtest2_events_session
        FOREIGN KEY (test_session_id)
        REFERENCES playtest2_sessions (test_session_id),
    INDEX idx_playtest2_events_session_time (test_session_id, occurred_at),
    INDEX idx_playtest2_events_name_time (event_name, occurred_at),
    INDEX idx_playtest2_events_game (game_type, session_seq, round_number),
    INDEX idx_playtest2_events_analytics_user_time (
        analytics_user_key,
        occurred_at
    )
);
