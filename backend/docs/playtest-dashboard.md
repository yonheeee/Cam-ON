# 플레이테스트 지표 대시보드

설문으로 수집하는 `재미 점수`, `다시 플레이할 의향`을 제외한 행동 지표 7개를
MySQL과 Metabase에서 확인하는 방법이다.

## 1. 지표 정의

| 지표 | 분모 | 분자 또는 계산 방식 |
| --- | --- | --- |
| 코스 완주율 | 시작된 코스 수 | `COURSE_FINISHED`가 기록된 코스 수 |
| 참가자 중간 이탈률 | 코스 시작 시 참가자 수 합계 | `PLAYING` 중 `PARTICIPANT_LEFT`가 기록된 고유 참가자 수 |
| 게임별 이탈률 | 게임 시작 시 참가자 수 합계 | 해당 게임 도중 최종 퇴장한 고유 참가자 수 |
| 결과 화면 도달률 | 완주 시 남아 있던 참가자 수 | `RESULT_SCREEN_VIEWED` 고유 참가자 수 |
| 재접속 성공률 | `PARTICIPANT_DISCONNECTED` 수 | 다음 상태가 `PARTICIPANT_RECONNECTED`인 연결 끊김 수 |
| 평균 코스 소요 시간 | 완주 코스 | `COURSE_STARTED`부터 `COURSE_FINISHED`까지 평균 |
| 같은 방 재플레이율 | 코스를 한 번 이상 시작한 방 수 | 코스를 두 번 이상 시작한 방 수 |
| 다른 방 재플레이율 | 익명 브라우저 수 | 서로 다른 방 2개 이상에 들어간 익명 브라우저 수 |
| 같은 날 재방문율 | 익명 브라우저·날짜 조합 | 같은 날 서로 다른 방 2개 이상에 들어간 조합 |
| 다음 날 재방문율 | 첫 방문 익명 브라우저 수 | 첫 방문 다음 날 다시 방문한 익명 브라우저 수 |

`PARTICIPANT_DISCONNECTED` 뒤에 재접속이나 최종 퇴장 이벤트가 아직 없는 경우,
재접속 성공률의 분모에는 포함하고 성공으로는 계산하지 않는다.

재플레이는 계정 기반 재방문이 아니라 **같은 방에서 코스를 다시 시작한 경우**다.
다른 방 및 날짜를 넘긴 재방문은 `localStorage`에 저장한 익명 분석 ID의 HMAC
해시값으로 연결한다. 이 값은 사람이나 계정이 아니라 동일 브라우저를 의미한다.
브라우저 데이터 삭제, 시크릿 모드, 다른 브라우저·기기 사용 시 새로운 방문자로 인식한다.

## 2. DB 뷰 적용

배포 DB에는 다음 파일을 순서대로 한 번씩 적용한다.

1. `V20260730_01__create_playtest_analytics_tables.sql`
2. `V20260730_02__add_playtest_attempt_number.sql`
3. `V20260730_03__create_playtest_metric_views.sql`
4. `V20260731_01__add_anonymous_analytics_user.sql`

세 번째 파일은 아래 읽기 전용 뷰를 만든다.

- `playtest_metric_course_attempts`
- `playtest_metric_game_sessions`
- `playtest_metric_disconnects`
- `playtest_metric_user_visits`

원본 로그는 계속 `playtest_events`와 `playtest_sessions`에 누적된다. 뷰는 데이터를
복사하지 않고 조회 시 최신 원본 로그를 계산하므로 별도의 갱신 작업이 필요 없다.

## 3. Metabase 실행

`backend` 디렉터리에서 실행한다.

```powershell
docker compose --profile analytics up -d metabase
```

브라우저에서 `http://localhost:3001`로 접속한다. 최초 실행은 이미지 다운로드와
초기화 때문에 수 분이 걸릴 수 있다.

Metabase는 기본적으로 `127.0.0.1`에만 바인딩되어 같은 PC에서만 접근할 수 있다.
또한 `analytics` 프로필을 지정하지 않은 일반 `docker compose up`에는 실행되지 않으므로
유저 테스트 서비스와 분리된다.

최초 설정 화면에서 MySQL 연결값은 다음과 같다.

| 항목 | 값 |
| --- | --- |
| Host | `mysql` |
| Port | `3306` |
| Database name | `.env`의 `MYSQL_DATABASE` |
| Username | `.env`의 `MYSQL_USER` |
| Password | `.env`의 `MYSQL_PASSWORD` |

관리자 계정은 팀 개발자만 아는 이메일과 강한 비밀번호로 만든다. 운영 서버에서 공유할
경우 Metabase 포트를 인터넷에 직접 열지 말고 VPN 또는 SSH 터널 안에서만 접근한다.

## 4. 대시보드 생성

Metabase에서 `새로 만들기 → SQL 쿼리`를 선택하고
`backend/docs/playtest-dashboard-queries.sql`의 번호별 쿼리를 각각 저장한다.

권장 카드 이름과 시각화는 다음과 같다.

| 번호 | 카드 이름 | 시각화 |
| --- | --- | --- |
| 1 | 코스 완주율 | 숫자 |
| 2 | 참가자 중간 이탈률 | 숫자 |
| 3 | 게임별 이탈률 | 막대 |
| 3-a | 상세 이탈 지점 | 표 |
| 4 | 결과 화면 도달률 | 숫자 |
| 5 | 재접속 성공률 | 숫자 |
| 6 | 평균 코스 소요 시간 | 숫자 |
| 7 | 같은 방 재플레이율 | 숫자 |

각 SQL의 `start_date`, `end_date` 변수 타입은 `날짜`로 지정한다. 카드를 하나의
`플레이테스트 핵심 지표` 대시보드에 추가한 뒤, 대시보드 날짜 필터를 두 변수에 연결한다.

## 5. 해석 시 주의사항

- 이탈 지점은 위치 로그 기능 배포 이후 데이터에만 존재한다.
- `TIMEOUT`은 15초 안에 복귀하지 못했다는 뜻이며, 사용자의 의도나 이탈 이유는 아니다.
- 결과 화면 이벤트는 브라우저가 서버로 전송하므로 바로 그 순간 네트워크가 끊기면 누락될 수 있다.
- 닌자 게임의 참가자 이탈 후 종료되지 않는 문제는 별도 게임 로직 버그이며, 지표 SQL은
  실제로 기록된 이벤트만 집계한다.
- 테스트 중 내부 개발자의 플레이가 섞이면 기간 필터 또는 `experiment_version`을 사용해 분리한다.
