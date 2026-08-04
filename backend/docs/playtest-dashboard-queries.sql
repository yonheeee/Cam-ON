-- Cam-ON playtest dashboard queries (MySQL 8)
-- Apply V20260730_03 before running these queries.
-- Metabase date variables use optional clauses so cards also work without filters.

-- 1. Course completion rate (%)
SELECT
    COUNT(*) AS started_courses,
    SUM(course_completed) AS completed_courses,
    ROUND(100.0 * SUM(course_completed) / NULLIF(COUNT(*), 0), 1)
        AS course_completion_rate_percent
FROM playtest_metric_course_attempts
WHERE 1 = 1
[[AND started_at >= {{start_date}}]]
[[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]];

-- 2. Participant mid-course dropout rate (%)
SELECT
    SUM(initial_player_count) AS participant_entries,
    SUM(dropout_player_count) AS dropout_players,
    ROUND(
        100.0 * SUM(dropout_player_count) / NULLIF(SUM(initial_player_count), 0),
        1
    ) AS participant_dropout_rate_percent
FROM playtest_metric_course_attempts
WHERE 1 = 1
[[AND started_at >= {{start_date}}]]
[[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]];

-- 3. Dropout rate by game (%)
SELECT
    game_type,
    SUM(player_count) AS participant_entries,
    SUM(dropout_player_count) AS dropout_players,
    ROUND(
        100.0 * SUM(dropout_player_count) / NULLIF(SUM(player_count), 0),
        1
    ) AS game_dropout_rate_percent
FROM playtest_metric_game_sessions
WHERE 1 = 1
[[AND started_at >= {{start_date}}]]
[[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]]
GROUP BY game_type
ORDER BY game_dropout_rate_percent DESC;

-- 3-a. Detailed dropout location
SELECT
    game_type,
    round_number,
    phase,
    exchange_number,
    turn_number,
    leave_reason,
    COUNT(*) AS dropout_players
FROM (
    SELECT
        COALESCE(
            game_type,
            JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.gameType')),
            'UNKNOWN'
        ) AS game_type,
        COALESCE(
            round_number,
            CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.roundNumber')) AS UNSIGNED)
        ) AS round_number,
        JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.phase')) AS phase,
        CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.exchangeNumber')) AS UNSIGNED)
            AS exchange_number,
        CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.turnNumber')) AS UNSIGNED)
            AS turn_number,
        JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.reason')) AS leave_reason
    FROM playtest_events
    WHERE event_name = 'PARTICIPANT_LEFT'
      AND JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.roomStatus')) = 'PLAYING'
    [[AND occurred_at >= {{start_date}}]]
    [[AND occurred_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]]
) exits
GROUP BY
    game_type,
    round_number,
    phase,
    exchange_number,
    turn_number,
    leave_reason
ORDER BY dropout_players DESC;

-- 4. Result-screen reach rate (%), based on every player who entered the course.
-- Using only the players left at completion makes this ~always 100%: finishing the course
-- means seeing the result screen, so mid-course dropouts vanish from the denominator.
-- LEAST guards against >100% when participants are replaced mid-course.
SELECT
    SUM(initial_player_count) AS initial_players,
    SUM(LEAST(result_viewer_count, initial_player_count)) AS result_viewers,
    ROUND(
        100.0 * SUM(LEAST(result_viewer_count, initial_player_count))
            / NULLIF(SUM(initial_player_count), 0),
        1
    ) AS result_screen_reach_rate_percent
FROM playtest_metric_course_attempts
WHERE 1 = 1
[[AND started_at >= {{start_date}}]]
[[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]];

-- 5. Reconnection success rate (%)
-- An unresolved disconnect is kept in the denominator and is not a success.
SELECT
    COUNT(*) AS disconnects,
    SUM(outcome = 'PARTICIPANT_RECONNECTED') AS successful_reconnections,
    ROUND(
        100.0 * SUM(outcome = 'PARTICIPANT_RECONNECTED') / NULLIF(COUNT(*), 0),
        1
    ) AS reconnection_success_rate_percent
FROM playtest_metric_disconnects
WHERE 1 = 1
[[AND disconnected_at >= {{start_date}}]]
[[AND disconnected_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]];

-- 6. Average completed-course duration
SELECT
    COUNT(*) AS completed_courses,
    ROUND(AVG(total_duration_seconds), 1) AS average_duration_seconds,
    SEC_TO_TIME(ROUND(AVG(total_duration_seconds))) AS average_duration
FROM playtest_metric_course_attempts
WHERE course_completed = TRUE
  AND total_duration_seconds IS NOT NULL
[[AND started_at >= {{start_date}}]]
[[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]];

-- 7. Same-room replay rate (%)
-- Denominator: rooms that started a course. Replay: a room with 2+ started attempts.
SELECT
    COUNT(*) AS rooms,
    SUM(started_attempts >= 2) AS replayed_rooms,
    ROUND(100.0 * SUM(started_attempts >= 2) / NULLIF(COUNT(*), 0), 1)
        AS same_room_replay_rate_percent
FROM (
    SELECT room_key, COUNT(*) AS started_attempts
    FROM playtest_metric_course_attempts
    WHERE 1 = 1
    [[AND started_at >= {{start_date}}]]
    [[AND started_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]]
    GROUP BY room_key
) room_attempts;

-- 8. Different-room replay rate by anonymous browser (%)
SELECT
    COUNT(*) AS anonymous_browsers,
    SUM(room_count >= 2) AS different_room_replayers,
    ROUND(100.0 * SUM(room_count >= 2) / NULLIF(COUNT(*), 0), 1)
        AS different_room_replay_rate_percent
FROM (
    SELECT
        analytics_user_key,
        COUNT(DISTINCT room_key) AS room_count
    FROM playtest_metric_user_visits
    WHERE 1 = 1
    [[AND entered_at >= {{start_date}}]]
    [[AND entered_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]]
    GROUP BY analytics_user_key
) browser_rooms;

-- 9. Same-day return rate by anonymous browser (%)
SELECT
    COUNT(*) AS browser_days,
    SUM(visit_count >= 2) AS returning_browser_days,
    ROUND(100.0 * SUM(visit_count >= 2) / NULLIF(COUNT(*), 0), 1)
        AS same_day_return_rate_percent
FROM (
    SELECT
        analytics_user_key,
        visit_date,
        COUNT(DISTINCT room_key) AS visit_count
    FROM playtest_metric_user_visits
    WHERE 1 = 1
    [[AND entered_at >= {{start_date}}]]
    [[AND entered_at < DATE_ADD({{end_date}}, INTERVAL 1 DAY)]]
    GROUP BY analytics_user_key, visit_date
) browser_days;

-- 10. Next-day return rate by anonymous browser (%)
WITH first_visits AS (
    SELECT
        analytics_user_key,
        MIN(visit_date) AS first_visit_date
    FROM playtest_metric_user_visits
    GROUP BY analytics_user_key
)
SELECT
    COUNT(*) AS new_anonymous_browsers,
    SUM(EXISTS (
        SELECT 1
        FROM playtest_metric_user_visits returned
        WHERE returned.analytics_user_key = first_visits.analytics_user_key
          AND returned.visit_date = DATE_ADD(
              first_visits.first_visit_date,
              INTERVAL 1 DAY
          )
    )) AS next_day_returners,
    ROUND(
        100.0 * SUM(EXISTS (
            SELECT 1
            FROM playtest_metric_user_visits returned
            WHERE returned.analytics_user_key = first_visits.analytics_user_key
              AND returned.visit_date = DATE_ADD(
                  first_visits.first_visit_date,
                  INTERVAL 1 DAY
              )
        )) / NULLIF(COUNT(*), 0),
        1
    ) AS next_day_return_rate_percent
FROM first_visits
WHERE 1 = 1
[[AND first_visit_date >= {{start_date}}]]
[[AND first_visit_date <= {{end_date}}]];
