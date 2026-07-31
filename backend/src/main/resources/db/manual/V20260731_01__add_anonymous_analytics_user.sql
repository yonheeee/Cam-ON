ALTER TABLE playtest_events
    ADD COLUMN analytics_user_key CHAR(64) NULL AFTER participant_key,
    ADD INDEX idx_playtest_events_analytics_user_time (
        analytics_user_key,
        occurred_at
    );

CREATE OR REPLACE VIEW playtest_metric_user_visits AS
SELECT
    pe.analytics_user_key,
    ps.room_key,
    pe.test_session_id,
    ps.attempt_number,
    MIN(pe.occurred_at) AS entered_at,
    DATE(MIN(pe.occurred_at)) AS visit_date,
    ps.app_version,
    ps.experiment_version
FROM playtest_events pe
JOIN playtest_sessions ps
    ON ps.test_session_id = pe.test_session_id
WHERE pe.event_name = 'ROOM_ENTERED'
  AND pe.analytics_user_key IS NOT NULL
GROUP BY
    pe.analytics_user_key,
    ps.room_key,
    pe.test_session_id,
    ps.attempt_number,
    ps.app_version,
    ps.experiment_version;
