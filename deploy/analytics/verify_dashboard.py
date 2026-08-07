#!/usr/bin/env python3
"""대시보드 카드 6개가 실제로 SQL 에러 없이 실행되는지 확인한다.

카드를 만드는 데 성공했다는 것과 카드가 값을 내놓는다는 것은 다른 얘기다 — 뷰가 빠졌거나
컬럼명이 바뀌면 생성은 되고 열어볼 때만 죽는다. 배포/스키마 변경 후 이걸 돌려 확인한다.

환경변수는 provision_metabase.py와 같다 (MB_ADMIN_EMAIL / MB_ADMIN_PASSWORD / MB_URL /
MB_DASHBOARD_NAME). 기본값은 provision_metabase.py가 만드는 2차 대시보드다 — 여기에 1차
이름이 박혀 있으면 2차를 프로비저닝하고 검증까지 통과해도 실제로 확인한 건 1차다.
"""

import json
import os
import sys
import urllib.error
import urllib.request

MB_URL = os.environ.get("MB_URL", "http://localhost:3001").rstrip("/")
DASHBOARD_NAME = os.environ.get(
    "MB_DASHBOARD_NAME", "플레이테스트 핵심 지표 (2차)"
)


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(f"{MB_URL}/api{path}", data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("X-Metabase-Session", token)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:500]
        sys.exit(f"{method} {path} 실패 ({error.code}): {detail}")


def find_dashboard(token):
    for model in ("dashboard",):
        items = call("GET", f"/collection/root/items?models={model}", token=token)
        for item in items.get("data", []):
            if item.get("name") == DASHBOARD_NAME:
                return item["id"]
    results = call("GET", "/dashboard", token=token) or []
    for dashboard in results:
        if dashboard.get("name") == DASHBOARD_NAME:
            return dashboard["id"]
    sys.exit(f"대시보드를 찾을 수 없다: {DASHBOARD_NAME}")


def main():
    email = os.environ.get("MB_ADMIN_EMAIL")
    password = os.environ.get("MB_ADMIN_PASSWORD")
    if not email or not password:
        sys.exit("MB_ADMIN_EMAIL / MB_ADMIN_PASSWORD 환경변수가 필요하다.")

    token = call("POST", "/session", {"username": email, "password": password})["id"]
    dashboard_id = find_dashboard(token)
    dashboard = call("GET", f"/dashboard/{dashboard_id}", token=token)
    dashcards = sorted(dashboard["dashcards"], key=lambda card: (card["row"], card["col"]))
    print(f"대시보드: {dashboard['name']} (id={dashboard_id}) | 카드 {len(dashcards)}개\n")

    failures = 0
    for dashcard in dashcards:
        name = dashcard["card"]["name"]
        result = call("POST", f"/card/{dashcard['card_id']}/query", {}, token=token)
        if result.get("status") == "completed":
            rows = result["data"]["rows"]
            columns = [column["display_name"] for column in result["data"]["cols"]]
            first = rows[0] if rows else None
            print(f"  OK   {name}: {len(rows)}행 / 컬럼 {columns}")
            print(f"       첫 행: {first}")
        else:
            failures += 1
            print(f"  FAIL {name}: {result.get('error')}")

    print(f"\n{len(dashcards) - failures}/{len(dashcards)} 카드 정상")
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
