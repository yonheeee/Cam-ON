-- 2차 유저테스트 지표 뷰. V20260806_03 다음에 적용한다.
--
-- 1차 뷰(playtest_metric_*)와 정의는 같고 읽는 테이블만 playtest2_*다. 1차 뷰는 그대로 두므로
-- 두 회차를 나란히 놓고 비교할 수 있다. 정의를 한 벌로 합치려면 UNION ALL + 회차 컬럼이
-- 필요한데, 그러면 "2차만" 보는 카드마다 필터가 하나씩 더 붙고 1차의 지저분한 구간이 다시
-- 딸려 들어온다 -- 회차 분리가 이 작업의 목적이라 뷰도 갈라 둔다.
--
-- 전부 CREATE OR REPLACE VIEW라 몇 번 적용해도 안전하다.

CREATE OR REPLACE VIEW playtest2_metric_course_attempts AS
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
FROM playtest2_sessions ps
LEFT JOIN playtest2_events pe
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

CREATE OR REPLACE VIEW playtest2_metric_game_sessions AS
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
    FROM playtest2_events
    WHERE event_name = 'GAME_SESSION_STARTED'
    GROUP BY test_session_id, game_type, session_seq
) started
LEFT JOIN playtest2_events left_event
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

CREATE OR REPLACE VIEW playtest2_metric_disconnects AS
SELECT
    disconnected.event_id,
    disconnected.test_session_id,
    disconnected.participant_key,
    disconnected.occurred_at AS disconnected_at,
    disconnected.game_type,
    disconnected.session_seq,
    (
        SELECT MIN(next_event.occurred_at)
        FROM playtest2_events next_event
        JOIN playtest2_sessions next_session
            ON next_session.test_session_id = next_event.test_session_id
        WHERE next_session.room_key = disconnected_session.room_key
          AND next_event.participant_key = disconnected.participant_key
          AND next_event.occurred_at > disconnected.occurred_at
          AND next_event.event_name IN ('PARTICIPANT_RECONNECTED', 'PARTICIPANT_LEFT')
    ) AS resolved_at,
    (
        SELECT next_event.event_name
        FROM playtest2_events next_event
        JOIN playtest2_sessions next_session
            ON next_session.test_session_id = next_event.test_session_id
        WHERE next_session.room_key = disconnected_session.room_key
          AND next_event.participant_key = disconnected.participant_key
          AND next_event.occurred_at > disconnected.occurred_at
          AND next_event.event_name IN ('PARTICIPANT_RECONNECTED', 'PARTICIPANT_LEFT')
        ORDER BY next_event.occurred_at, next_event.server_received_at
        LIMIT 1
    ) AS outcome
FROM playtest2_events disconnected
JOIN playtest2_sessions disconnected_session
    ON disconnected_session.test_session_id = disconnected.test_session_id
WHERE disconnected.event_name = 'PARTICIPANT_DISCONNECTED';

CREATE OR REPLACE VIEW playtest2_metric_user_visits AS
SELECT
    pe.analytics_user_key,
    ps.room_key,
    pe.test_session_id,
    ps.attempt_number,
    MIN(pe.occurred_at) AS entered_at,
    DATE(MIN(pe.occurred_at)) AS visit_date,
    ps.app_version,
    ps.experiment_version
FROM playtest2_events pe
JOIN playtest2_sessions ps
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
