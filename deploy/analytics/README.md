# 플레이테스트 분석 콘솔 배포 런북 (EC2 + Metabase)

`backend/docs/metabase-local-dashboard-ai-guide.md`는 **각자 로컬 PC**에 Metabase를 띄우는
절차다. 그러면 팀원마다 자기 PC의 MySQL만 보게 되고(내 플레이만 조회됨), 대시보드도 각자
다시 만들어야 한다. 이 문서는 같은 대시보드를 **EC2 한 곳에** 띄워서 팀 전체가 같은 운영
데이터를 보게 하는 절차다.

## 지금 어떤 상태인가

- 이벤트 수집은 **Metabase와 무관하게** 이미 돌아간다. 배포된 Spring 백엔드가 방/게임 진행
  중에 `playtest_sessions` / `playtest_events`에 직접 쓴다. Metabase는 그걸 **읽어서 보여주는
  도구**일 뿐이므로, Metabase가 꺼져 있어도 데이터는 계속 쌓인다.
- Metabase는 EC2에서 `restart: unless-stopped`로 상시 기동한다. 보는 사람의 PC 상태와 무관하다.

## 접근 방식: SSH 터널

Metabase는 EC2의 `127.0.0.1:3001`에만 바인딩된다. 여기서 `127.0.0.1`은 **EC2 자신의**
loopback이지 접속하는 사람의 PC가 아니다. 즉 컨테이너는 EC2에서 24시간 돌지만, 그 포트는
EC2 외부에서 직접 열리지 않는다.

왜 이렇게 하나:

- 이 호스트는 ufw에 22/443/8989만 열려 있는데도 80이 외부에서 열린다. Docker가 published
  포트를 nat/DOCKER-USER 체인에 직접 넣어 ufw INPUT을 우회하기 때문이다. 그래서 compose에서
  `0.0.0.0`으로 바꾸는 순간, ufw에 아무 것도 열지 않아도 분석 콘솔이 인터넷에 노출된다.
- 분석 콘솔은 방문 이력·이탈 지점 등 플레이테스트 원본을 다 볼 수 있는 화면이라, 공개해 두고
  Metabase 로그인만 믿는 것보다 네트워크 단계에서 막는 편이 낫다.

### 대시보드 보는 방법

터널을 연 사람의 PC에서만 열린다. 볼 때만 켜면 되고, 꺼도 수집은 계속된다.

```bash
ssh -i <pem 경로> -L 3001:localhost:3001 ubuntu@i15b110.p.ssafy.io
```

터널을 띄운 채로 브라우저에서:

```text
http://localhost:3001
```

로그인 계정은 EC2의 `/opt/camon/analytics/.mb-admin.env`(mode 600)에 있다. 팀원별 계정을
따로 주려면 Metabase 관리자 화면에서 초대한다(사람마다 pem을 돌리는 것보다 이게 맞다).

### 공개 URL로 바꾸고 싶다면

터널 없이 `https://...`로 열려면 **Metabase를 별도 포트로 노출해야 한다.** Metabase는 서브패스
호스팅(`/metabase` 같은 프리픽스)을 지원하지 않아서 기존 443 도메인에 경로만 추가하는 방식은
쓸 수 없고, 서브도메인은 `*.p.ssafy.io` DNS 권한이 없어 만들 수 없다.

따라서 필요한 것:

1. AWS 보안그룹(SSAFY 관리)에 해당 포트 인바운드 허용 — **팀에서 직접 해야 한다.**
2. `deploy/docker-compose.prod.yml`의 caddy 서비스에 그 포트 publish 추가
3. `deploy/Caddyfile`에 `{$DOMAIN}:<포트> { reverse_proxy metabase:3000 }` 블록 추가
   (인증서는 80번 HTTP-01로 이미 받은 것을 재사용한다)
4. 최소한 `basic_auth`를 걸 것 — 공개 포트에 분석 콘솔을 그냥 올리지 않는다.

2~4번은 Jenkins가 배포 때 덮어쓰는 파일이라 **main 머지 후에 적용된다.**

## 앱 배포와 분리된 이유

`deploy/docker-compose.prod.yml`에 Metabase를 넣지 않았다. Jenkins가 main 배포 때 그 파일을
덮어쓰고 `docker compose up -d --remove-orphans`를 돌리는데, 그 파일에 없는 컨테이너는
orphan으로 지워진다. compose 프로젝트 이름을 `camon-analytics`로 분리하면 앱 배포가 이
컨테이너를 건드리지 않는다.

대가: Jenkins가 이 파일을 서버에 설치해 주지 않으므로, 아래 최초 설치는 **한 번 수동으로** 한다.

## 최초 설치 (EC2에서 한 번)

리포가 서버에 상주하지 않으므로(Jenkins는 빌드 후 워크스페이스를 지운다) 필요한 파일만 올린다.

```bash
# 로컬 리포 루트에서
ssh -i <pem> ubuntu@i15b110.p.ssafy.io "mkdir -p /opt/camon/analytics/db-manual"
scp -i <pem> deploy/analytics/{docker-compose.analytics.yml,apply-metric-views.sh,provision_metabase.py,verify_dashboard.py} \
    ubuntu@i15b110.p.ssafy.io:/opt/camon/analytics/
scp -i <pem> backend/src/main/resources/db/manual/V20260730_01__*.sql \
             backend/src/main/resources/db/manual/V20260730_03__*.sql \
             backend/src/main/resources/db/manual/V20260731_01__*.sql \
    ubuntu@i15b110.p.ssafy.io:/opt/camon/analytics/db-manual/
```

EC2에서:

```bash
cd /opt/camon/analytics
chmod +x apply-metric-views.sh
docker compose -f docker-compose.analytics.yml up -d

# playtest_metric_* 뷰 4개 생성 (필수 — 아래 "뷰" 항목 참고)
./apply-metric-views.sh /opt/camon/analytics/db-manual

# 관리자 자격 생성 (한 번만)
umask 077
printf 'MB_ADMIN_EMAIL=<이메일>\nMB_ADMIN_PASSWORD=Camon-%s-A1!\n' "$(openssl rand -hex 8)" \
    > .mb-admin.env

# 초기 설정 + DB 연결 + 대시보드 + 카드 6개 생성 (몇 번 돌려도 안전)
set -a; . ./.mb-admin.env; set +a
python3 provision_metabase.py
python3 verify_dashboard.py
```

## 왜 뷰를 따로 만들어야 하나

배포 백엔드는 Spring `local` 프로필(`ddl-auto: update`)로 돌아서 `playtest_sessions` /
`playtest_events` **테이블**은 JPA가 자동으로 만든다. 하지만 대시보드 카드가 읽는 것은 테이블이
아니라 **뷰 4개**(`playtest_metric_course_attempts` / `_game_sessions` / `_disconnects` /
`_user_visits`)이고, 뷰는 엔티티가 없어서 JPA가 만들어 주지 않는다. 이걸 빼먹으면 카드 전부가
"Table doesn't exist"로 죽는다. `apply-metric-views.sh`가 이 간극만 메운다.

`ddl-auto: update`가 만든 테이블에는 `V20260730_01`이 정의한 보조 인덱스 일부가 없을 수 있다.
현재 데이터 규모에서는 조회 성능에 영향이 없어 그대로 두었다 — Flyway를 도입하면 정리 대상이다.

## ANALYTICS_HMAC_SECRET

`analytics_user_key`는 참가자 UUID를 이 시크릿으로 HMAC-SHA256한 값이다. 익명 브라우저 수,
재방문율 카드가 전부 이 키로 계산된다.

- 설정하지 않으면 백엔드가 `application.yml`의 기본값(`local-analytics-secret-change-me`)을
  쓴다. 운영에서는 반드시 임의 값으로 바꾼다.
- **한 번 정하면 바꾸지 않는다.** 시크릿을 바꾸면 같은 브라우저가 다른 키로 해싱돼서, 그
  이전 데이터와 재방문 여부를 이어서 볼 수 없다.
- 서버 `/opt/camon/.env`에 넣고, `deploy/docker-compose.prod.yml`이 backend에 전달한다
  (값이 비면 백엔드가 기동 시점에 실패한다 — 조용히 기본값으로 돌아가는 것보다 낫다).

```bash
# EC2에서 한 번. 이미 있으면 덮어쓰지 않는다.
grep -q '^ANALYTICS_HMAC_SECRET=' /opt/camon/.env \
  || printf 'ANALYTICS_HMAC_SECRET=%s\n' "$(openssl rand -hex 32)" \
     | sudo tee -a /opt/camon/.env >/dev/null
```

## 재실행 / 갱신

- 카드 SQL이나 배치를 바꿨을 때: `provision_metabase.py`를 다시 올려서 실행하면 된다.
  컬렉션/대시보드/카드는 이름으로 찾아 갱신하고, 대시보드 배치는 전체를 교체한다.
- 스키마(뷰)가 바뀌었을 때: `apply-metric-views.sh` 재실행 → `verify_dashboard.py`로 확인.
- Metabase 버전을 올릴 때: compose의 이미지 태그를 바꾸고 `up -d`. 대시보드/계정은
  `camon-analytics_metabase-data` 볼륨의 H2 파일에 있으므로 컨테이너 교체로 사라지지 않는다.

## 확인용 SQL

```bash
docker exec -it camon-mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
```

```sql
SELECT COUNT(*) FROM playtest_sessions;
SELECT COUNT(*) FROM playtest_events;
SELECT event_name, occurred_at, game_type, session_seq, round_number
FROM playtest_events ORDER BY occurred_at DESC LIMIT 20;
```
