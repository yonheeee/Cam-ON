#!/usr/bin/env python3
"""Metabase 초기 설정과 플레이테스트 대시보드를 REST API로 만든다.

가이드(backend/docs/metabase-local-dashboard-ai-guide.md)의 브라우저 클릭 절차를 그대로
API로 옮긴 것이다. 배포 서버는 브라우저를 띄울 수 없고, 손으로 만들면 팀원/인스턴스마다
카드가 미묘하게 달라지므로(가이드가 애초에 그 문제 때문에 쓰였다) 스크립트로 고정한다.

몇 번 실행해도 결과가 같다:
- 이미 초기 설정된 인스턴스면 로그인만 하고 넘어간다
- 컬렉션/대시보드/카드는 이름으로 찾아 있으면 갱신, 없으면 생성한다
- 대시보드 배치는 매번 원하는 전체 집합으로 교체한다

회차:
이 스크립트가 만드는 것은 **2차** 대시보드이고 카드는 playtest2_* 를 읽는다. 백엔드가 2차
테이블에만 기록하므로 1차 뷰를 읽는 카드는 더 이상 늘지 않는다.

이미 EC2에 만들어져 있는 1차 대시보드("플레이테스트 핵심 지표")는 건드리지 않는다 —
이름이 달라서 이 스크립트의 upsert 대상에서 빠지고, 1차 뷰도 그대로 살아 있어 두 회차를
나란히 볼 수 있다. 카드 이름에까지 "(2차)"를 붙인 건 같은 컬렉션 안에서 이름으로 upsert하기
때문이다. 이름이 겹치면 1차 카드의 SQL을 2차로 덮어써서 1차 대시보드가 조용히 망가진다.

필요 환경변수:
  MB_ADMIN_EMAIL     관리자 이메일
  MB_ADMIN_PASSWORD  관리자 비밀번호
선택:
  MB_URL             기본 http://localhost:3001
  CAMON_ENV_FILE     MySQL 접속값을 읽을 env 파일. 기본 /opt/camon/.env
  MB_ADMIN_FIRST_NAME / MB_ADMIN_LAST_NAME
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

MB_URL = os.environ.get("MB_URL", "http://localhost:3001").rstrip("/")
ENV_FILE = os.environ.get("CAMON_ENV_FILE", "/opt/camon/.env")
COLLECTION_NAME = "우리의 분석"
DASHBOARD_NAME = "플레이테스트 핵심 지표 (2차)"
DATABASE_NAME = "Cam-ON"

session_token = None


def read_env_file(path):
    """deploy/.env에서 KEY=VALUE를 읽는다. 비밀번호는 반환값 안에만 머문다(출력 금지)."""
    values = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    except OSError as error:
        sys.exit(f"env 파일을 읽을 수 없다: {path} ({error})")
    return values


def request(method, path, body=None, allow_status=()):
    url = f"{MB_URL}/api{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if session_token:
        req.add_header("X-Metabase-Session", session_token)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read().decode("utf-8")
            # Set-Cookie로만 세션을 주는 버전도 있어서 양쪽 다 받아 둔다.
            cookie = response.headers.get("Set-Cookie") or ""
            token = None
            if "metabase.SESSION=" in cookie:
                token = cookie.split("metabase.SESSION=", 1)[1].split(";", 1)[0]
            return (json.loads(raw) if raw else None), token
    except urllib.error.HTTPError as error:
        if error.code in allow_status:
            return None, None
        detail = error.read().decode("utf-8", "replace")[:800]
        sys.exit(f"{method} {path} 실패 ({error.code}): {detail}")
    except urllib.error.URLError as error:
        sys.exit(f"{method} {path} 연결 실패: {error}")


def wait_for_metabase(attempts=60, interval=5):
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(f"{MB_URL}/api/health", timeout=10) as response:
                if json.loads(response.read().decode("utf-8")).get("status") == "ok":
                    print(f"Metabase 준비 완료 ({attempt}회 시도)")
                    return
        except Exception:
            pass
        time.sleep(interval)
    sys.exit(f"Metabase가 {attempts * interval}초 안에 뜨지 않았다: {MB_URL}")


def login_or_setup(email, password, first_name, last_name):
    global session_token
    properties, _ = request("GET", "/session/properties")
    setup_token = properties.get("setup-token")
    # setup-token은 초기 설정을 마친 뒤에도 응답에 남아 있다. 그것만 보고 판단하면 재실행 때
    # /api/setup을 또 호출해 403으로 죽는다 — 사용자 존재 여부는 has-user-setup으로 본다.
    already_setup = bool(properties.get("has-user-setup"))

    if setup_token and not already_setup:
        print("초기 설정 진행(관리자 계정 생성)")
        # DB 연결은 여기서 같이 넣지 않고 아래에서 따로 만든다 — 연결 실패가 계정 생성까지
        # 되돌리면 재실행 시 상태가 어긋난다.
        body = {
            "token": setup_token,
            "user": {
                "first_name": first_name,
                "last_name": last_name,
                "email": email,
                "password": password,
                "site_name": "Cam-ON",
            },
            "prefs": {
                "site_name": "Cam-ON",
                "site_locale": "ko",
                "allow_tracking": False,
            },
        }
        _, token = request("POST", "/setup", body)
        session_token = token

    if not session_token:
        print("기존 인스턴스 — 로그인")
        payload, token = request(
            "POST", "/session", {"username": email, "password": password}
        )
        session_token = token or (payload or {}).get("id")

    if not session_token:
        sys.exit("세션 토큰을 얻지 못했다.")


def ensure_database(env):
    databases, _ = request("GET", "/database")
    items = databases.get("data", databases) if isinstance(databases, dict) else databases
    for database in items:
        if database.get("name") == DATABASE_NAME:
            print(f"DB 연결 이미 존재: id={database['id']}")
            return database["id"]

    print(f"DB 연결 생성: {DATABASE_NAME}")
    body = {
        "engine": "mysql",
        "name": DATABASE_NAME,
        "details": {
            # Metabase는 camon-network 안에 있으므로 호스트용 포트가 아니라 컨테이너 주소로 붙는다.
            "host": "mysql",
            "port": 3306,
            "dbname": env["MYSQL_DATABASE"],
            "user": env["MYSQL_USER"],
            "password": env["MYSQL_PASSWORD"],
            "ssl": False,
            # MySQL 8은 caching_sha2_password를 쓰는데 SSL을 끄면 서버 공개키를 받아올 수 없어
            # "RSA public key is not available client side"로 연결이 거부된다.
            "advanced-options": True,
            "additional-options": "allowPublicKeyRetrieval=true&useSSL=false",
            "tunnel-enabled": False,
        },
        "is_full_sync": True,
        "auto_run_queries": True,
        "schedules": {},
    }
    database, _ = request("POST", "/database", body)
    return database["id"]


def sync_database(database_id, attempts=24, interval=5):
    request("POST", f"/database/{database_id}/sync_schema")
    for _ in range(attempts):
        database, _ = request("GET", f"/database/{database_id}")
        if database.get("initial_sync_status") == "complete":
            print("스키마 동기화 완료")
            return
        time.sleep(interval)
    print("경고: 스키마 동기화가 아직 진행 중이다(네이티브 SQL 카드는 영향 없음).")


def ensure_collection():
    """카드가 들어갈 컬렉션 id를 돌려준다. 루트를 쓸 때는 문자열 "root"를 돌려준다.

    가이드가 말하는 `우리의 분석`은 사실 Metabase 기본 루트 컬렉션("Our analytics")의 한국어
    번역명이다. 그래서 이름만 보고 매칭하면 id가 정수가 아닌 "root"가 잡히고, 그걸
    collection_id로 보내면 400이 난다. 같은 이름의 하위 컬렉션을 새로 만들면 `우리의 분석`
    안에 `우리의 분석`이 생겨 더 헷갈리므로, 루트가 그 이름이면 루트를 그대로 쓴다.
    """
    collections, _ = request("GET", "/collection")
    root_matches_name = False
    for collection in collections:
        if collection.get("name") != COLLECTION_NAME or collection.get("archived"):
            continue
        if isinstance(collection.get("id"), int):
            print(f"컬렉션 이미 존재: id={collection['id']}")
            return collection["id"]
        root_matches_name = True

    if root_matches_name:
        print(f"루트 컬렉션이 이미 '{COLLECTION_NAME}'이라 그대로 사용")
        return "root"

    print(f"컬렉션 생성: {COLLECTION_NAME}")
    collection, _ = request(
        "POST", "/collection", {"name": COLLECTION_NAME, "parent_id": None}
    )
    return collection["id"]


def payload_collection_id(collection_id):
    """API 본문에 넣을 값. 루트는 id 문자열이 아니라 null로 표현한다."""
    return None if collection_id == "root" else collection_id


def collection_items(collection_id, model):
    payload, _ = request("GET", f"/collection/{collection_id}/items?models={model}")
    return payload.get("data", []) if isinstance(payload, dict) else (payload or [])


def ensure_dashboard(collection_id):
    for item in collection_items(collection_id, "dashboard"):
        if item.get("name") == DASHBOARD_NAME:
            print(f"대시보드 이미 존재: id={item['id']}")
            return item["id"]
    print(f"대시보드 생성: {DASHBOARD_NAME}")
    dashboard, _ = request(
        "POST",
        "/dashboard",
        {
            "name": DASHBOARD_NAME,
            "collection_id": payload_collection_id(collection_id),
        },
    )
    return dashboard["id"]


def ensure_card(card_spec, database_id, collection_id, existing):
    body = {
        "name": card_spec["name"],
        "display": card_spec["display"],
        "visualization_settings": card_spec.get("visualization_settings", {}),
        "collection_id": payload_collection_id(collection_id),
        "dataset_query": {
            "database": database_id,
            "type": "native",
            "native": {"query": card_spec["sql"], "template-tags": {}},
        },
    }
    card_id = existing.get(card_spec["name"])
    if card_id:
        print(f"카드 갱신: {card_spec['name']} (id={card_id})")
        request("PUT", f"/card/{card_id}", body)
        return card_id
    print(f"카드 생성: {card_spec['name']}")
    card, _ = request("POST", "/card", body)
    return card["id"]


def place_cards(dashboard_id, placements):
    """대시보드 배치를 원하는 전체 집합으로 교체한다(id를 음수로 주면 새 배치로 생성된다)."""
    dashcards = []
    for index, (card_id, row, col, size_x, size_y) in enumerate(placements):
        dashcards.append(
            {
                "id": -(index + 1),
                "card_id": card_id,
                "row": row,
                "col": col,
                "size_x": size_x,
                "size_y": size_y,
                "series": [],
                "parameter_mappings": [],
                "visualization_settings": {},
            }
        )
    request("PUT", f"/dashboard/{dashboard_id}", {"dashcards": dashcards})
    print(f"대시보드 배치 {len(dashcards)}개 적용")


CARDS = [
    {
        "key": "completion",
        "name": "코스 완주율 (2차)",
        "display": "scalar",
        "sql": """SELECT
    ROUND(
        100.0 * SUM(course_completed) / NULLIF(COUNT(*), 0),
        1
    ) AS `코스 완주율 (%)`
FROM playtest2_metric_course_attempts;""",
    },
    {
        "key": "summary",
        "name": "플레이테스트 요약 지표 (2차)",
        "display": "table",
        "sql": """SELECT
    (
        SELECT ROUND(
            100.0 * SUM(dropout_player_count)
                / NULLIF(SUM(initial_player_count), 0),
            1
        )
        FROM playtest2_metric_course_attempts
    ) AS `참가자 중간 이탈률 (%)`,
    (
        -- 전체 참가자 기준: 결과 화면 도달 인원 ÷ 최초 참가 인원.
        -- 완주자만 분모로 쓰면 중간에 이탈한 사람이 계산에서 빠져 거의 항상 100%가 나온다
        -- (완주했으면 결과 화면을 보기 때문). 최초 참가 인원을 분모로 두면 이탈까지 반영된다.
        -- LEAST는 안전장치다 — 도중에 참가자가 교체되면 도달 인원이 최초 인원을 넘어
        -- 100%를 초과할 수 있다.
        SELECT ROUND(
            100.0 * SUM(LEAST(result_viewer_count, initial_player_count))
                / NULLIF(SUM(initial_player_count), 0),
            1
        )
        FROM playtest2_metric_course_attempts
    ) AS `전체 참가자 기준 결과 화면 도달률 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(outcome = 'PARTICIPANT_RECONNECTED')
                / NULLIF(COUNT(*), 0),
            1
        )
        FROM playtest2_metric_disconnects
    ) AS `재접속 성공률 (%)`,
    (
        SELECT ROUND(AVG(total_duration_seconds), 1)
        FROM playtest2_metric_course_attempts
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
            FROM playtest2_metric_course_attempts
            GROUP BY room_key
        ) room_attempts
    ) AS `같은 방 재플레이율 (%)`;""",
    },
    {
        "key": "game_dropout",
        "name": "게임별 이탈률 (2차)",
        "display": "bar",
        "visualization_settings": {
            "graph.dimensions": ["게임"],
            "graph.metrics": ["게임별 이탈률 (%)"],
        },
        "sql": """SELECT
    game_type AS `게임`,
    SUM(player_count) AS `참가 인원`,
    SUM(dropout_player_count) AS `이탈 인원`,
    ROUND(
        100.0 * SUM(dropout_player_count) / NULLIF(SUM(player_count), 0),
        1
    ) AS `게임별 이탈률 (%)`
FROM playtest2_metric_game_sessions
GROUP BY game_type
ORDER BY `게임별 이탈률 (%)` DESC;""",
    },
    {
        "key": "exit_points",
        "name": "상세 이탈 지점 (2차)",
        "display": "table",
        "sql": """SELECT
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
    FROM playtest2_events
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
ORDER BY `이탈 인원` DESC;""",
    },
    {
        "key": "user_summary",
        "name": "익명 사용자 요약 (2차)",
        "display": "table",
        "sql": """SELECT
    (
        SELECT COUNT(DISTINCT analytics_user_key)
        FROM playtest2_metric_user_visits
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
            FROM playtest2_metric_user_visits
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
            FROM playtest2_metric_user_visits
            GROUP BY analytics_user_key, visit_date
        ) browser_days
    ) AS `같은 날 재방문율 (%)`,
    (
        SELECT ROUND(
            100.0 * SUM(EXISTS (
                SELECT 1
                FROM playtest2_metric_user_visits returned
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
            FROM playtest2_metric_user_visits
            GROUP BY analytics_user_key
        ) first_visits
    ) AS `다음 날 재방문율 (%)`;""",
    },
    {
        "key": "visit_detail",
        "name": "익명 방문 상세 (2차)",
        "display": "table",
        "sql": """SELECT
    LEFT(analytics_user_key, 8) AS `익명 브라우저`,
    LEFT(room_key, 8) AS `방`,
    entered_at AS `방문 시각`,
    visit_date AS `방문 날짜`,
    app_version AS `앱 버전`,
    experiment_version AS `실험 버전`
FROM playtest2_metric_user_visits
ORDER BY entered_at DESC;""",
    },
]

# 가이드 7번 "권장 대시보드 배치". Metabase 그리드는 24칼럼이다.
LAYOUT = {
    "completion": (0, 0, 24, 3),
    "summary": (3, 0, 24, 4),
    "game_dropout": (7, 0, 12, 6),
    "exit_points": (7, 12, 12, 6),
    "user_summary": (13, 0, 24, 4),
    "visit_detail": (17, 0, 24, 7),
}


def main():
    email = os.environ.get("MB_ADMIN_EMAIL")
    password = os.environ.get("MB_ADMIN_PASSWORD")
    if not email or not password:
        sys.exit("MB_ADMIN_EMAIL / MB_ADMIN_PASSWORD 환경변수가 필요하다.")

    env = read_env_file(ENV_FILE)
    missing = [
        key
        for key in ("MYSQL_DATABASE", "MYSQL_USER", "MYSQL_PASSWORD")
        if not env.get(key)
    ]
    if missing:
        sys.exit(f"{ENV_FILE}에 다음 값이 없다: {', '.join(missing)}")

    wait_for_metabase()
    login_or_setup(
        email,
        password,
        os.environ.get("MB_ADMIN_FIRST_NAME", "Cam-ON"),
        os.environ.get("MB_ADMIN_LAST_NAME", "Analytics"),
    )

    database_id = ensure_database(env)
    sync_database(database_id)
    collection_id = ensure_collection()
    dashboard_id = ensure_dashboard(collection_id)

    existing = {
        item["name"]: item["id"] for item in collection_items(collection_id, "card")
    }
    card_ids = {
        card["key"]: ensure_card(card, database_id, collection_id, existing)
        for card in CARDS
    }
    place_cards(
        dashboard_id,
        [(card_ids[key],) + LAYOUT[key] for key in LAYOUT],
    )

    print(f"\n완료: {MB_URL}/dashboard/{dashboard_id}")


if __name__ == "__main__":
    main()
