# 로컬 Metabase 대시보드 생성 가이드 (AI 실행용)

이 문서는 각 팀원의 로컬 PC에서 AI 에이전트가 Cam-ON 플레이테스트 대시보드를 동일하게
생성하기 위한 실행 지침이다. AI는 설명만 제공하지 말고, 브라우저를 제어할 수 있다면 아래
순서대로 Metabase 설정과 카드 생성을 직접 완료한다.

## 완료 조건

- `http://localhost:3001`에서 Metabase에 로그인할 수 있다.
- `Cam-ON` MySQL 데이터베이스가 연결되어 있다.
- `우리의 분석` 컬렉션이 존재한다.
- `플레이테스트 핵심 지표` 대시보드에 아래 6개 카드가 배치되어 있다.
  1. 코스 완주율
  2. 플레이테스트 요약 지표
  3. 게임별 이탈률
  4. 상세 이탈 지점
  5. 익명 사용자 요약
  6. 익명 방문 상세

## 1. 사전 확인

프로젝트 루트에서 현재 브랜치와 파일 존재 여부를 확인한다.

```powershell
git branch --show-current
Test-Path backend/compose.yml
Test-Path backend/src/main/resources/db/manual/V20260730_01__create_playtest_analytics_tables.sql
Test-Path backend/src/main/resources/db/manual/V20260731_01__add_anonymous_analytics_user.sql
```

`backend/.env`에는 최소한 다음 값이 있어야 한다. 비밀번호 값은 출력하거나 문서에 복사하지 않는다.

```dotenv
MYSQL_DATABASE=...
MYSQL_USER=...
MYSQL_PASSWORD=...
MYSQL_ROOT_PASSWORD=...
ANALYTICS_HMAC_SECRET=...
```

## 2. Docker 실행

`backend` 디렉터리에서 MySQL, 백엔드, Redis와 Metabase를 실행한다.

```powershell
cd backend
docker compose --profile analytics up -d --build
docker compose ps
```

다음 컨테이너가 실행 중이어야 한다.

- `camon-mysql`
- `camon-backend`
- `camon-redis`
- `camon-metabase`

Metabase 접속 주소:

```text
http://localhost:3001
```

## 3. 분석 스키마 확인

MySQL Workbench 또는 MySQL 클라이언트에서 다음 객체가 존재하는지 확인한다.

```sql
SHOW TABLES LIKE 'playtest_%';
```

필요한 객체:

- `playtest_sessions`
- `playtest_events`
- `playtest_metric_course_attempts`
- `playtest_metric_game_sessions`
- `playtest_metric_disconnects`
- `playtest_metric_user_visits`

객체가 없다면 `backend/src/main/resources/db/manual`의 SQL을 다음 순서로 한 번씩 적용한다.

1. `V20260730_01__create_playtest_analytics_tables.sql`
2. `V20260730_02__add_playtest_attempt_number.sql`
3. `V20260730_03__create_playtest_metric_views.sql`
4. `V20260731_01__add_anonymous_analytics_user.sql`

이미 테이블이나 컬럼이 존재하면 해당 마이그레이션을 무작정 재실행하지 말고 현재 스키마를 먼저
확인한다.

## 4. Metabase 초기 설정 및 MySQL 연결

초기 설정 화면이 나오면 언어를 한국어로 선택하고 팀원 개인 관리자 계정을 생성한다.

MySQL 연결값:

| 항목 | 값 |
| --- | --- |
| 표시 이름 | `Cam-ON` |
| 호스트 | `mysql` |
| 포트 | `3306` |
| 데이터베이스 이름 | `backend/.env`의 `MYSQL_DATABASE` |
| 사용자 이름 | `backend/.env`의 `MYSQL_USER` |
| 암호 | `backend/.env`의 `MYSQL_PASSWORD` |
| SSL | 끔 |
| SSH 터널 | 끔 |

Metabase와 MySQL은 `camon-network`에 함께 있으므로 호스트 PC용 `localhost:3307`이 아니라
컨테이너 주소인 `mysql:3306`을 사용한다.

다음 RSA 오류가 발생하면 상세 옵션의 JDBC 추가 옵션에 값을 설정한다.

```text
RSA public key is not available client side
```

```text
allowPublicKeyRetrieval=true&useSSL=false
```

연결 후 다음 메뉴에서 스키마를 동기화한다.

```text
관리자 설정 → 데이터베이스 → Cam-ON → 데이터베이스 스키마 동기화
```

## 5. 컬렉션과 대시보드 생성

1. 최상위 컬렉션에 `우리의 분석` 컬렉션을 만든다.
2. 해당 컬렉션 안에 `플레이테스트 핵심 지표` 대시보드를 만든다.
3. 아래 SQL 질문도 모두 `우리의 분석` 컬렉션에 저장한다.
4. 질문을 저장할 때 `플레이테스트 핵심 지표` 대시보드에 추가한다.

## 6. 카드 생성

Metabase에서 매번 다음 순서를 사용한다.

```text
새로 만들기 → SQL 쿼리 → 데이터베이스 Cam-ON 선택 → SQL 입력
→ 실행 → 시각화 선택 → 저장 → 우리의 분석 → 대시보드에 추가
```

### 카드 1: 코스 완주율

시각화: `숫자`

```sql
SELECT
    ROUND(
        100.0 * SUM(course_completed) / NULLIF(COUNT(*), 0),
        1
    ) AS `코스 완주율 (%)`
FROM playtest_metric_course_attempts;
```

### 카드 2: 플레이테스트 요약 지표

시각화: `테이블`

```sql
SELECT
    (
        SELECT ROUND(
            100.0 * SUM(dropout_player_count)
                / NULLIF(SUM(initial_player_count), 0),
            1
        )
        FROM playtest_metric_course_attempts
    ) AS `참가자 중간 이탈률 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(LEAST(result_viewer_count, completed_player_count))
                / NULLIF(SUM(completed_player_count), 0),
            1
        )
        FROM playtest_metric_course_attempts
        WHERE course_completed = TRUE
    ) AS `결과 화면 도달률 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(outcome = 'PARTICIPANT_RECONNECTED')
                / NULLIF(COUNT(*), 0),
            1
        )
        FROM playtest_metric_disconnects
    ) AS `재접속 성공률 (%)`,
    (
        SELECT ROUND(AVG(total_duration_seconds), 1)
        FROM playtest_metric_course_attempts
        WHERE course_completed = TRUE
          AND total_duration_seconds IS NOT NULL
    ) AS `평균 코스 시간 (초)`,
    (
        SELECT ROUND(
            100.0 * SUM(started_attempts >= 2) / NULLIF(COUNT(*), 0),
            1
        )
        FROM (
            SELECT room_key, COUNT(*) AS started_attempts
            FROM playtest_metric_course_attempts
            GROUP BY room_key
        ) room_attempts
    ) AS `같은 방 재플레이율 (%)`;
```

### 카드 3: 게임별 이탈률

시각화: `막대 차트`

- X축: `게임`
- Y축: `게임별 이탈률 (%)`

```sql
SELECT
    game_type AS `게임`,
    SUM(player_count) AS `참가 인원`,
    SUM(dropout_player_count) AS `이탈 인원`,
    ROUND(
        100.0 * SUM(dropout_player_count) / NULLIF(SUM(player_count), 0),
        1
    ) AS `게임별 이탈률 (%)`
FROM playtest_metric_game_sessions
GROUP BY game_type
ORDER BY `게임별 이탈률 (%)` DESC;
```

### 카드 4: 상세 이탈 지점

시각화: `테이블`

```sql
SELECT
    game_type AS `게임`,
    round_number AS `라운드`,
    phase AS `진행 단계`,
    exchange_number AS `닌자 교환 횟수`,
    turn_number AS `몸으로 말해요 턴`,
    leave_reason AS `퇴장 사유`,
    COUNT(*) AS `이탈 인원`
FROM (
    SELECT
        COALESCE(
            game_type,
            JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.gameType')),
            'UNKNOWN'
        ) AS game_type,
        COALESCE(
            round_number,
            CAST(
                JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.roundNumber'))
                AS UNSIGNED
            )
        ) AS round_number,
        JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.phase')) AS phase,
        CAST(
            JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.exchangeNumber'))
            AS UNSIGNED
        ) AS exchange_number,
        CAST(
            JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.turnNumber'))
            AS UNSIGNED
        ) AS turn_number,
        JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.reason')) AS leave_reason
    FROM playtest_events
    WHERE event_name = 'PARTICIPANT_LEFT'
      AND JSON_UNQUOTE(
          JSON_EXTRACT(properties_json, '$.roomStatus')
      ) = 'PLAYING'
) exits
GROUP BY
    game_type,
    round_number,
    phase,
    exchange_number,
    turn_number,
    leave_reason
ORDER BY `이탈 인원` DESC;
```

### 카드 5: 익명 사용자 요약

시각화: `테이블`

```sql
SELECT
    (
        SELECT COUNT(DISTINCT analytics_user_key)
        FROM playtest_metric_user_visits
    ) AS `익명 브라우저 수`,
    (
        SELECT ROUND(
            100.0 * SUM(room_count >= 2) / NULLIF(COUNT(*), 0),
            1
        )
        FROM (
            SELECT
                analytics_user_key,
                COUNT(DISTINCT room_key) AS room_count
            FROM playtest_metric_user_visits
            GROUP BY analytics_user_key
        ) browser_rooms
    ) AS `다른 방 재플레이율 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(visit_count >= 2) / NULLIF(COUNT(*), 0),
            1
        )
        FROM (
            SELECT
                analytics_user_key,
                visit_date,
                COUNT(DISTINCT room_key) AS visit_count
            FROM playtest_metric_user_visits
            GROUP BY analytics_user_key, visit_date
        ) browser_days
    ) AS `같은 날 재방문율 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(EXISTS (
                SELECT 1
                FROM playtest_metric_user_visits returned
                WHERE returned.analytics_user_key =
                    first_visits.analytics_user_key
                  AND returned.visit_date = DATE_ADD(
                      first_visits.first_visit_date,
                      INTERVAL 1 DAY
                  )
            )) / NULLIF(COUNT(*), 0),
            1
        )
        FROM (
            SELECT
                analytics_user_key,
                MIN(visit_date) AS first_visit_date
            FROM playtest_metric_user_visits
            GROUP BY analytics_user_key
        ) first_visits
    ) AS `다음 날 재방문율 (%)`;
```

### 카드 6: 익명 방문 상세

시각화: `테이블`

```sql
SELECT
    LEFT(analytics_user_key, 8) AS `익명 브라우저`,
    LEFT(room_key, 8) AS `방`,
    entered_at AS `방문 시각`,
    visit_date AS `방문 날짜`,
    app_version AS `앱 버전`,
    experiment_version AS `실험 버전`
FROM playtest_metric_user_visits
ORDER BY entered_at DESC;
```

## 7. 권장 대시보드 배치

```text
[ 코스 완주율                         ]
[ 플레이테스트 요약 지표              ]
[ 게임별 이탈률 ][ 상세 이탈 지점       ]
[ 익명 사용자 요약                    ]
[ 익명 방문 상세                      ]
```

각 카드는 값과 컬럼이 잘리지 않을 정도로 너비를 조정한 뒤 대시보드의 `저장`을 누른다.

## 8. 최종 검증

다음 SQL로 원본 로그가 존재하는지 확인한다.

```sql
SELECT COUNT(*) AS session_count FROM playtest_sessions;
SELECT COUNT(*) AS event_count FROM playtest_events;

SELECT
    event_name,
    occurred_at,
    game_type,
    session_seq,
    round_number
FROM playtest_events
ORDER BY occurred_at DESC
LIMIT 20;
```

대시보드를 새로고침한 뒤 다음을 확인한다.

- SQL 오류가 표시되지 않는다.
- 데이터가 없을 때 카드가 `데이터 없음` 또는 `NULL`로 표시되는 것은 정상이다.
- 테스트 플레이 후 `playtest_sessions`, `playtest_events` 개수가 증가한다.
- 방 화면 진입 후 `ROOM_ENTERED` 이벤트가 생성된다.
- 결과 화면 도달 후 `RESULT_SCREEN_VIEWED` 이벤트가 생성된다.
- 같은 브라우저가 다른 방에 들어가면 `익명 방문 상세`에 같은 익명 브라우저 값으로 행이 추가된다.

## 9. 문제 해결

### `No database selected`

Workbench에서 왼쪽 `SCHEMAS`의 `camon` 데이터베이스를 더블 클릭하거나 SQL 맨 위에 다음을 실행한다.

```sql
USE camon;
```

실제 데이터베이스 이름이 다르면 `backend/.env`의 `MYSQL_DATABASE` 값을 사용한다.

### Metabase에서 뷰가 보이지 않음

```text
관리자 설정 → 데이터베이스 → Cam-ON
→ 데이터베이스 스키마 동기화
→ 필드 값 다시 스캔
```

### 팀원마다 값이 다름

정상이다. 배포 전에는 각 PC의 로컬 MySQL에 로그가 별도로 저장되므로 각자 플레이한 데이터만
조회된다.

### 대시보드가 다른 팀원 PC에 자동으로 생기지 않음

정상이다. 질문과 대시보드는 각 Metabase 인스턴스의 애플리케이션 DB에 저장된다. 이 문서를
사용해 각 로컬 인스턴스에서 동일하게 생성한다.
