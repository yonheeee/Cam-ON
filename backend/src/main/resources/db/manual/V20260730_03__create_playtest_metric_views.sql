-- Apply after V20260730_01 and V20260730_02.
-- These views are read-only metric definitions shared by Workbench and Metabase.

CREATE OR REPLACE VIEW playtest_metric_course_attempts AS
SELECT
    ps.test_session_id,
    ps.room_key,
    ps.attempt_number,
    ps.started_at,
    ps.finished_at,
    ps.app_version,
    ps.experiment_version,
    ps.initial_player_count,
    ps.completed_player_count,
    ps.course_completed,
    ps.total_duration_seconds,
    COUNT(DISTINCT CASE
        WHEN pe.event_name = 'RESULT_SCREEN_VIEWED' THEN pe.participant_key
    END) AS result_viewer_count,
    COUNT(DISTINCT CASE
        WHEN pe.event_name = 'PARTICIPANT_LEFT'
          AND JSON_UNQUOTE(JSON_EXTRACT(pe.properties_json, '$.roomStatus')) = 'PLAYING'
        THEN pe.participant_key
    END) AS dropout_player_count
FROM playtest_sessions ps
LEFT JOIN playtest_events pe
    ON pe.test_session_id = ps.test_session_id
WHERE ps.started_at IS NOT NULL
GROUP BY
    ps.test_session_id,
    ps.room_key,
    ps.attempt_number,
    ps.started_at,
    ps.finished_at,
    ps.app_version,
    ps.experiment_version,
    ps.initial_player_count,
    ps.completed_player_count,
    ps.course_completed,
    ps.total_duration_seconds;

CREATE OR REPLACE VIEW playtest_metric_game_sessions AS
SELECT
    started.test_session_id,
    started.game_type,
    started.session_seq,
    started.started_at,
    started.player_count,
    COUNT(DISTINCT CASE
        WHEN left_event.event_name = 'PARTICIPANT_LEFT'
        THEN left_event.participant_key
    END) AS dropout_player_count
FROM (
    SELECT
        test_session_id,
        game_type,
        session_seq,
        MIN(occurred_at) AS started_at,
        MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.playerCount')) AS UNSIGNED))
            AS player_count
    FROM playtest_events
    WHERE event_name = 'GAME_SESSION_STARTED'
    GROUP BY test_session_id, game_type, session_seq
) started
LEFT JOIN playtest_events left_event
    ON left_event.test_session_id = started.test_session_id
   AND left_event.participant_key IS NOT NULL
   AND left_event.occurred_at >= started.started_at
   AND COALESCE(
       left_event.game_type,
       JSON_UNQUOTE(JSON_EXTRACT(left_event.properties_json, '$.gameType'))
   ) = started.game_type
   AND COALESCE(
       left_event.session_seq,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(left_event.properties_json, '$.sessionSeq')) AS UNSIGNED)
   ) = started.session_seq
   AND JSON_UNQUOTE(JSON_EXTRACT(left_event.properties_json, '$.roomStatus')) = 'PLAYING'
GROUP BY
    started.test_session_id,
    started.game_type,
    started.session_seq,
    started.started_at,
    started.player_count;

CREATE OR REPLACE VIEW playtest_metric_disconnects AS
SELECT
    disconnected.event_id,
    disconnected.test_session_id,
    disconnected.participant_key,
    disconnected.occurred_at AS disconnected_at,
    disconnected.game_type,
    disconnected.session_seq,
    (
        SELECT MIN(next_event.occurred_at)
        FROM playtest_events next_event
        JOIN playtest_sessions next_session
            ON next_session.test_session_id = next_event.test_session_id
        WHERE next_session.room_key = disconnected_session.room_key
          AND next_event.participant_key = disconnected.participant_key
          AND next_event.occurred_at > disconnected.occurred_at
          AND next_event.event_name IN ('PARTICIPANT_RECONNECTED', 'PARTICIPANT_LEFT')
    ) AS resolved_at,
    (
        SELECT next_event.event_name
        FROM playtest_events next_event
        JOIN playtest_sessions next_session
            ON next_session.test_session_id = next_event.test_session_id
        WHERE next_session.room_key = disconnected_session.room_key
          AND next_event.participant_key = disconnected.participant_key
          AND next_event.occurred_at > disconnected.occurred_at
          AND next_event.event_name IN ('PARTICIPANT_RECONNECTED', 'PARTICIPANT_LEFT')
        ORDER BY next_event.occurred_at, next_event.server_received_at
        LIMIT 1
    ) AS outcome
FROM playtest_events disconnected
JOIN playtest_sessions disconnected_session
    ON disconnected_session.test_session_id = disconnected.test_session_id
WHERE disconnected.event_name = 'PARTICIPANT_DISCONNECTED';
