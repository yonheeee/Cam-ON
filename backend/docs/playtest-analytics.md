# Playtest analytics

플레이테스트 분석 데이터는 게임 진행 상태용 Redis와 분리하여 MySQL에 계속 보관한다.
코스 시작 한 번을 `test_session_id` 하나로 취급한다. 같은 방에서 다시 플레이하면 같은
`room_key` 아래 `attempt_number`가 1씩 증가한다. 닉네임, 토큰, 영상, 랜드마크, 채팅 원문은
저장하지 않는다.

## 배포 DB 준비

Flyway가 아직 없으므로 다음 SQL을 배포 DB에 한 번 적용한다.

```text
src/main/resources/db/manual/V20260730_01__create_playtest_analytics_tables.sql
```

`V20260730_01`을 이미 적용한 DB는 다음 보정 SQL도 한 번 적용한다.

```text
src/main/resources/db/manual/V20260730_02__add_playtest_attempt_number.sql
```

운영 환경에서는 반드시 `ANALYTICS_HMAC_SECRET`을 충분히 긴 임의 문자열로 설정한다.
버전 비교가 필요하면 `APP_VERSION`, `EXPERIMENT_VERSION`도 배포 환경변수로 지정한다.

## 서버에서 자동으로 수집하는 이벤트

- `ROOM_CREATED`
- `PARTICIPANT_JOINED`
- `READY_CHANGED`
- `PARTICIPANT_DISCONNECTED`
- `PARTICIPANT_RECONNECTED`
- `PARTICIPANT_LEFT`
- `COURSE_STARTED`
- `GAME_SESSION_STARTED`
- `GAME_SESSION_FINISHED`
- `GAME_SESSION_SKIPPED`
- `COURSE_FINISHED`

## 클라이언트 이벤트 API

```http
POST /api/rooms/{roomId}/analytics/events
Authorization: Bearer {accessToken}
Content-Type: application/json
```

```json
{
  "analyticsUserId": "2a2eed88-df8d-43bb-b8a4-fdb69272eb35",
  "appVersion": "frontend-2026.07.30",
  "experimentVersion": "ninja-guide-v2",
  "events": [
    {
      "eventId": "80f5fb5e-339a-4d48-ab2f-a91d9b7f09c0",
      "eventName": "NINJA_RECOGNITION_WINDOW",
      "occurredAt": "2026-07-30T06:04:17.442Z",
      "gameType": "NINJA",
      "sessionSeq": 1,
      "roundNumber": 2,
      "properties": {
        "attemptCount": 18,
        "validCount": 11,
        "averageConfidence": 0.74,
        "averageLatencyMs": 183
      }
    }
  ]
}
```

한 요청에는 최대 20개 이벤트를 보낼 수 있다. `eventId`는 재시도 시에도 같은 UUID를 사용해야
중복 저장이 방지된다. 인증된 참가자가 현재 해당 방에 있을 때만 요청을 받는다.

허용된 클라이언트 이벤트:

- `ROOM_ENTERED`
- `CAMERA_PERMISSION_RESULT`
- `RECOGNITION_TEST_RESULT`
- `RESULT_SCREEN_VIEWED`
- `NINJA_RECOGNITION_WINDOW`

클라이언트는 최초 접속 시 브라우저 `localStorage`에 익명 UUID를 생성하고 모든
분석 요청의 `analyticsUserId`로 전달한다. 서버는 UUID 원문을 저장하지 않고
`analytics_user_key` HMAC 해시값만 저장한다. 같은 브라우저의 다른 방 플레이와
날짜가 다른 재방문을 연결할 수 있지만, 브라우저 데이터 삭제·시크릿 모드·다른
기기 사용은 새로운 브라우저로 집계된다.

## 이탈 위치 해석

`PARTICIPANT_DISCONNECTED`와 `PARTICIPANT_LEFT`의 `properties_json`에는 가능한 경우
이탈 당시 서버 상태가 함께 저장된다.

```json
{
  "reason": "TIMEOUT",
  "roomStatus": "PLAYING",
  "gameType": "NINJA",
  "sessionSeq": 2,
  "roundNumber": 1,
  "exchangeNumber": 4,
  "phase": "ROUND"
}
```

몸으로 말해요는 `roundNumber`, `turnNumber`, `phase`가 저장된다. 대기실이나 결과 화면에서는
`roomStatus`만 있을 수 있다. `TIMEOUT`은 15초 뒤 방이 삭제될 수 있으므로, 서버는 직전
`PARTICIPANT_DISCONNECTED` 위치를 이어받아 저장한다.

## 저장 결과 확인

최근 테스트 세션:

```sql
SELECT
    BIN_TO_UUID(test_session_id) AS test_session_id,
    LEFT(room_key, 8) AS room,
    attempt_number,
    created_at,
    started_at,
    finished_at,
    app_version,
    experiment_version,
    initial_player_count,
    completed_player_count,
    course_completed,
    total_duration_seconds
FROM playtest_sessions
ORDER BY created_at DESC
LIMIT 20;
```

한 세션의 시간순 이벤트:

```sql
SELECT
    event_name,
    participant_key,
    game_type,
    session_seq,
    round_number,
    occurred_at,
    properties_json
FROM playtest_events
WHERE test_session_id = UUID_TO_BIN(:testSessionId)
ORDER BY occurred_at, server_received_at;
```

완주율:

```sql
SELECT
    COUNT(*) AS started_sessions,
    SUM(course_completed) AS completed_sessions,
    ROUND(100 * SUM(course_completed) / NULLIF(COUNT(*), 0), 1)
        AS completion_rate_percent
FROM playtest_sessions
WHERE started_at IS NOT NULL;
```

이탈 사유:

```sql
SELECT
    JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.reason')) AS leave_reason,
    COUNT(*) AS leave_count
FROM playtest_events
WHERE event_name = 'PARTICIPANT_LEFT'
GROUP BY leave_reason
ORDER BY leave_count DESC;
```

게임·라운드별 이탈:

```sql
SELECT
    JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.gameType')) AS game_type,
    JSON_EXTRACT(properties_json, '$.roundNumber') AS round_number,
    JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.phase')) AS phase,
    JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.reason')) AS leave_reason,
    COUNT(*) AS leave_count
FROM playtest_events
WHERE event_name = 'PARTICIPANT_LEFT'
GROUP BY game_type, round_number, phase, leave_reason
ORDER BY leave_count DESC;
```
