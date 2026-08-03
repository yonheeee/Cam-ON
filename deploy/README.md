# 배포 런북 (EC2 + Docker Compose + Caddy)

프론트(Vite 빌드 정적 파일)와 백엔드(Spring)를 EC2 한 대에 Docker Compose로 올리고, Caddy가
정적 파일 서빙 + `/api`,`/ws` 리버스프록시 + HTTPS(Let's Encrypt 자동 발급/갱신)를 전부
담당한다. mysql/redis도 같은 인스턴스에 컨테이너로 띄운다(별도 관리형 서비스 안 씀 — 임시
프로젝트 규모에 안 맞음).

브라우저가 카메라/마이크(`getUserMedia`)를 허용하려면 `localhost`가 아닌 이상 HTTPS가
필수라 이 배포가 필요하다.

플레이테스트 지표 대시보드(Metabase)는 앱 배포와 수명을 분리한 별도 compose 프로젝트로 띄운다 —
절차와 이유는 [analytics/README.md](analytics/README.md)에 있다.

## 사전 준비 (AWS 콘솔 / 도메인 등록기관에서 직접)

1. **도메인 확보** — 가비아/Route53/Namecheap 등에서 구매하거나, 비용 없이 빠르게 하려면
   [DuckDNS](https://www.duckdns.org/) 같은 무료 서브도메인도 된다. 어느 쪽이든 아래 절차는
   동일 — 도메인 문자열만 바뀐다.
2. **EC2 인스턴스 생성** — Ubuntu 22.04/24.04, 최소 `t3.small`(mysql + backend + frontend
   빌드를 동시에 돌리기엔 `t3.micro`는 빠듯함). 보안 그룹:
   - 22(SSH) — 내 IP만
   - 80(HTTP) — 0.0.0.0/0 (Let's Encrypt ACME 챌린지 + 443 리다이렉트에 필요)
   - 443(HTTPS) — 0.0.0.0/0
3. **Elastic IP 할당 후 인스턴스에 연결** — 재시작해도 IP가 안 바뀌게.
4. **도메인 A 레코드 → Elastic IP** 연결. (DuckDNS는 자체 대시보드에서 IP 등록)

## EC2 서버 설정

```bash
# Docker + Compose plugin 설치 (Ubuntu)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# 재로그인 후 아래부터

git clone <repo-url> camon
cd camon/deploy
cp .env.example .env
vim .env   # DOMAIN, CADDY_ACME_EMAIL, MYSQL_*, LIVEKIT_* 실제 값으로 채우기
```

`.env`는 `.gitignore`에 걸려 있어야 한다(실제 시크릿이 들어가므로 커밋 금지 —
`backend/.env`와 동일한 취급).

## 기동

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

최초 기동 순서: redis/mysql 헬스체크 통과 → backend 기동(DevNinjaDataSeeder가 gesture/skill
등 시드 데이터 자동 채움) → frontend-builder가 `npm run build`로 정적 파일을 만들어
`frontend-dist` 볼륨에 씀 → caddy가 그 볼륨을 서빙하면서 `$DOMAIN`으로 Let's Encrypt 인증서
자동 발급.

## 검증

```bash
# 백엔드 API가 프록시로 뚫리는지
curl -sI https://<도메인>/api/sessions -X POST -H "Content-Type: application/json" -d '{"nickname":"tester"}'

# 프론트 정적 파일이 나오는지
curl -sI https://<도메인>/

# 인증서 발급 로그 확인
docker logs camon-caddy | grep -i certificate
```

브라우저로 `https://<도메인>`에 접속해서 카메라 권한 프롬프트가 실제로 뜨는지, 방 생성/입장이
되는지 확인한다.

## 갱신/재배포

프론트나 백엔드 코드가 바뀌면:

```bash
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

`frontend-builder`는 매번 다시 빌드해서 `frontend-dist` 볼륨 내용을 덮어쓰고 종료된다
(상시 컨테이너 아님). Caddy는 이미 발급받은 인증서를 `caddy-data` 볼륨에 보관하고 만료 전
자동 갱신하므로, 재배포 때마다 인증서를 새로 받지 않는다.

## 알아둘 점

- **배포 환경도 Spring `local` 프로필을 그대로 쓴다** — 대기방→게임 자동시작 흐름이 아직
  없어서 닌자 게임을 실제로 시작시키는 유일한 방법인 `DevNinjaSeedController`
  (`POST /api/dev/ninja/seed`)가 `local` 프로필에서만 활성화되기 때문. 이 흐름이 정식으로
  생기면 별도 `prod` 프로필 + 영구 RSA 키(`JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH`)로
  전환하는 게 맞다(지금은 JWT 서명키가 백엔드 재시작마다 새로 생성돼서, 재시작 시 접속 중이던
  세션 토큰이 전부 무효화된다 — 게스트 토큰 기반 임시 세션이라 재로그인 정도로 끝나는 절충).
- mysql/redis/backend 포트는 호스트에 노출하지 않는다(Caddy만 80/443 오픈) — 필요하면
  SSH 터널로 접속.
- 도메인이 나중에 바뀌면 `deploy/.env`의 `DOMAIN`만 바꾸고 재기동하면 된다(백엔드 CORS/WS
  허용 오리진도 `FRONTEND_BASE_URL` 하나로 같이 따라감 — 자바 코드 안 건드림).
