# Cam-ON 포팅 매뉴얼

> 기준 브랜치: `main`  
> 기준 커밋: `e1168576c0c840b572029b72e6d9a019482e02f4`  
> 운영 URL: `https://i15b110.p.ssafy.io`

## 1. GitLab 소스 클론 이후 빌드 및 배포할 수 있도록 정리한 문서

### 1.1 사용 제품 종류, 설정값 및 버전

#### 운영 환경

| 항목 | 제품 및 버전 | 설정값 |
| --- | --- | --- |
| 운영체제 | Ubuntu 24.04.3 LTS | EC2 |
| JVM | OpenJDK 21.0.11 | Jenkins 실행 JVM |
| Backend JVM | Eclipse Temurin 21 | JDK 21 build, JRE 21 runtime |
| Gradle | Wrapper 8.14.3 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| WAS | Spring Boot Embedded Tomcat 3.5.16 | 내부 포트 8080 |
| Web Server | Caddy 2-alpine | 외부 포트 80, 443 |
| Frontend Runtime | Node.js 24-alpine | Vite build 전용 |
| Docker Engine | 29.6.2 | Ubuntu EC2 |
| Docker Compose | v5.3.1 | 운영 컨테이너 구성 |
| Jenkins | 2.568.1 | Multibranch Pipeline |
| MySQL | 8.4 | 내부 포트 3306 |
| Redis | 7.4-alpine | 내부 포트 6379, AOF 사용 |
| Metabase | v0.50.32 | EC2 `127.0.0.1:3001` |
| AI Python | Python 3.11 | FastAPI/Uvicorn 실행 |

#### Frontend 주요 버전

| 제품 | 버전 |
| --- | --- |
| React / React DOM | 19.2.7 |
| TypeScript | 5.9.3 |
| Vite | 8.1.1 |
| LiveKit Client | 2.20.1 |
| LiveKit React Components | 2.9.23 |
| MediaPipe Tasks Vision | 0.10.35 |

#### Backend 주요 버전

| 제품 | 버전 |
| --- | --- |
| Spring Boot | 3.5.16 |
| Spring Dependency Management | 1.1.7 |
| LiveKit Java Server SDK | 0.13.0 |
| Springdoc OpenAPI | 2.8.6 |

#### AI 및 손동작 인식 주요 버전

| 제품 | 버전 |
| --- | --- |
| Transformers | 4.49 이상 |
| PyTorch | CUDA 12.8 wheel |
| MediaPipe | 0.10.14 |
| TensorFlow | 2.15.1 |
| OpenCV | 4.11.0.86 |
| NumPy | 1.26.4 |

#### IDE 버전

| IDE | 버전 | 설정 |
| --- | --- | --- |
| IntelliJ IDEA | 2026.1.4 | Project SDK 21, UTF-8, Gradle Wrapper |
| Visual Studio Code | 1.132.0 x64 | UTF-8, TypeScript workspace 버전 |

### 1.2 소스 클론 및 빌드

#### GitLab clone

```bash
git clone https://lab.ssafy.com/s15-webmobile1-sub1/S15P11B110.git
cd S15P11B110
git switch main
git pull --ff-only origin main
```

#### Backend

```bash
cd backend
cp .env.example .env
docker compose up -d mysql redis
./gradlew clean test bootJar
./gradlew bootRun
```

Windows PowerShell에서는 `./gradlew.bat`을 사용합니다.

Backend 확인 주소:

```text
http://localhost:8080
http://localhost:8080/actuator/health
http://localhost:8080/swagger-ui/index.html
```

#### Frontend

```bash
cd frontend
cp .env.example .env
npm ci
npm run lint
npm run build
npm run dev
```

Frontend 기본 주소는 `http://localhost:5173`입니다.

#### AI 서버

```bash
cd ai
conda create -n camon-siglip python=3.11
conda activate camon-siglip
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8100
```

AI 서버 확인 주소:

```text
http://localhost:8100/health
http://localhost:8100/test
```

#### 닌자 손동작 모델

```bash
cd hand-gesture-recognition-mediapipe
python -m venv .venv

# Windows
.venv\Scripts\activate

# Linux/macOS
source .venv/bin/activate

pip install -r requirements.txt
python app.py
```

### 1.3 빌드 시 사용되는 환경변수

#### 운영 환경변수: `/opt/camon/.env`

```dotenv
DOMAIN=i15b110.p.ssafy.io

BACKEND_IMAGE=camon-backend:local
FRONTEND_IMAGE=camon-web:local
SPRING_PROFILES_ACTIVE=local

MYSQL_DATABASE=camon
MYSQL_USER=camon
MYSQL_PASSWORD=<MySQL 일반 계정 비밀번호>
MYSQL_ROOT_PASSWORD=<MySQL root 비밀번호>

ANALYTICS_HMAC_SECRET=<openssl rand -hex 32 결과>
APP_VERSION=<배포 커밋 또는 버전>
EXPERIMENT_VERSION=baseline

LIVEKIT_URL=wss://<LiveKit 프로젝트>.livekit.cloud
LIVEKIT_API_KEY=<LiveKit API Key>
LIVEKIT_API_SECRET=<LiveKit API Secret>

AI_BASE_URL=https://<AI 서버 Tailscale Funnel 주소>
```

| 환경변수 | 사용 위치 | 내용 |
| --- | --- | --- |
| `DOMAIN` | Caddy, Frontend build | 운영 도메인 |
| `BACKEND_IMAGE` | Docker Compose | Backend 이미지 태그 |
| `FRONTEND_IMAGE` | Docker Compose | Frontend 이미지 태그 |
| `SPRING_PROFILES_ACTIVE` | Backend | Spring profile |
| `MYSQL_DATABASE` | MySQL, Backend | DB 이름 |
| `MYSQL_USER` | MySQL, Backend | 일반 DB 계정 |
| `MYSQL_PASSWORD` | MySQL, Backend | 일반 DB 계정 비밀번호 |
| `MYSQL_ROOT_PASSWORD` | MySQL | root 비밀번호 |
| `ANALYTICS_HMAC_SECRET` | Backend | 익명 사용자 식별 HMAC 키 |
| `APP_VERSION` | Backend 분석 | 애플리케이션 버전 |
| `EXPERIMENT_VERSION` | Backend 분석 | 실험 버전 |
| `LIVEKIT_URL` | Backend, Frontend build | LiveKit WebSocket URL |
| `LIVEKIT_API_KEY` | Backend | LiveKit 토큰 발급 Key |
| `LIVEKIT_API_SECRET` | Backend | LiveKit 토큰 서명 Secret |
| `AI_BASE_URL` | Frontend build | 물건 인식 AI HTTPS URL |

#### Frontend build 환경변수

```dotenv
VITE_API_BASE_URL=https://i15b110.p.ssafy.io
VITE_WS_BASE_URL=wss://i15b110.p.ssafy.io
VITE_LIVEKIT_URL=wss://<LiveKit 프로젝트>.livekit.cloud
VITE_AI_BASE_URL=https://<AI 서버 주소>
```

`VITE_*` 환경변수는 Vite build 시 브라우저 번들에 포함됩니다. 따라서 Secret 값을 넣지 않습니다.
환경변수 변경 후에는 Frontend 이미지를 다시 빌드합니다.

### 1.4 배포 절차

#### EC2 배포 디렉터리

```text
/opt/camon/
├── .env
├── Caddyfile
├── docker-compose.prod.yml
├── deploy.sh
├── generate-jwt-keys.sh
└── secrets/
    ├── jwt-private.pem
    └── jwt-public.pem
```

#### JWT 키 생성

```bash
cd /opt/camon
chmod +x generate-jwt-keys.sh
./generate-jwt-keys.sh
chmod 600 secrets/jwt-private.pem
chmod 644 secrets/jwt-public.pem
```

#### 수동 최초 배포

```bash
COMMIT_TAG=$(git rev-parse --short=12 HEAD)

docker build -t "camon-backend:${COMMIT_TAG}" backend

docker build \
  --build-arg VITE_API_BASE_URL="https://i15b110.p.ssafy.io" \
  --build-arg VITE_WS_BASE_URL="wss://i15b110.p.ssafy.io" \
  --build-arg VITE_LIVEKIT_URL="<LIVEKIT_URL>" \
  --build-arg VITE_AI_BASE_URL="<AI_BASE_URL>" \
  -t "camon-web:${COMMIT_TAG}" frontend

sudo install -m 0644 deploy/docker-compose.prod.yml /opt/camon/docker-compose.prod.yml
sudo install -m 0644 deploy/Caddyfile /opt/camon/Caddyfile
sudo install -m 0755 deploy/deploy.sh /opt/camon/deploy.sh

sudo -u jenkins env \
  BACKEND_IMAGE="camon-backend:${COMMIT_TAG}" \
  FRONTEND_IMAGE="camon-web:${COMMIT_TAG}" \
  DOMAIN="i15b110.p.ssafy.io" \
  /opt/camon/deploy.sh
```

#### Jenkins 자동 배포

| 브랜치 | 동작 |
| --- | --- |
| `develop-backend`, Backend 기능 브랜치 | Backend test, bootJar |
| `develop-frontend`, Frontend 기능 브랜치 | Frontend lint, build |
| `develop` | Backend/Frontend 검사 및 이미지 생성 |
| `main` | 검사, 이미지 생성, 운영 배포 |

Backend CI:

```bash
SPRING_PROFILES_ACTIVE=test ./gradlew clean test bootJar --no-daemon
```

Frontend CI:

```bash
npm ci
npm run lint
npm run build
```

운영 배포는 `main`에서만 수행합니다. 현재 `.gitlab-ci.yml`은 없으며 GitLab Runner 배포는 사용하지
않습니다.

### 1.5 배포 시 특이사항

- MySQL, Redis, Backend 포트는 호스트 외부에 공개하지 않습니다.
- Caddy만 80과 443 포트를 외부에 공개합니다.
- Caddy가 `DOMAIN`을 기준으로 Let's Encrypt 인증서를 자동 발급·갱신합니다.
- Frontend 정적 파일은 `frontend-builder`가 `frontend-dist` named volume에 복사한 후 종료합니다.
- 운영 Backend는 현재 `SPRING_PROFILES_ACTIVE=local`과 Hibernate `ddl-auto:update`를 사용합니다.
- `LIVEKIT_URL`은 Backend와 Frontend가 같은 LiveKit 프로젝트 값을 사용해야 합니다.
- 운영 페이지가 HTTPS이므로 `AI_BASE_URL`도 HTTPS여야 합니다.
- `deploy.sh`는 배포 후 `/actuator/health`를 확인하고 실패하면 직전 이미지로 롤백합니다.
- Metabase는 앱과 별도 Compose 프로젝트 `camon-analytics`로 실행합니다.
- 실제 운영 배포는 Jenkins `main` Pipeline에서만 수행합니다.

### 1.6 DB 접속 정보 및 주요 계정·프로퍼티 파일 목록

#### DB 접속 정보

| 항목 | 값 |
| --- | --- |
| DBMS | MySQL 8.4 |
| Docker hostname | `mysql` |
| Port | `3306` |
| Database | `/opt/camon/.env`의 `MYSQL_DATABASE` |
| 일반 계정 | `/opt/camon/.env`의 `MYSQL_USER` |
| 일반 계정 비밀번호 | `/opt/camon/.env`의 `MYSQL_PASSWORD` |
| root 비밀번호 | `/opt/camon/.env`의 `MYSQL_ROOT_PASSWORD` |
| JDBC URL | `jdbc:mysql://mysql:3306/<DB_NAME>` |

운영 MySQL 접속:

```bash
docker exec -it camon-mysql sh -c \
  'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
```

#### 주요 파일 목록

| 파일 | 정의 내용 |
| --- | --- |
| `deploy/.env.example` | 운영 환경변수 템플릿 |
| `/opt/camon/.env` | 운영 DB, LiveKit, AI 실제 설정 |
| `backend/.env.example` | 로컬 Backend 환경변수 템플릿 |
| `frontend/.env.example` | 로컬 Frontend 환경변수 템플릿 |
| `backend/src/main/resources/application.yml` | Spring 공통 속성 |
| `backend/src/main/resources/application-local.yml` | JPA local profile |
| `deploy/docker-compose.prod.yml` | 운영 컨테이너, volume, network |
| `deploy/Caddyfile` | HTTPS, REST, STOMP, 정적 파일 라우팅 |
| `Jenkinsfile` | CI/CD Pipeline |
| `/opt/camon/secrets/jwt-private.pem` | JWT 서명 개인키 |
| `/opt/camon/secrets/jwt-public.pem` | JWT 검증 공개키 |
| Jenkins Credentials Store | GitLab 접근 계정 |
| `/opt/camon/analytics/.mb-admin.env` | Metabase 관리자 계정 |

---

## 2. 프로젝트에서 사용하는 외부 서비스 정보를 정리한 문서

### 2.1 AWS EC2 및 도메인

용도: 운영 Frontend, Backend, MySQL, Redis, Caddy, Jenkins, Metabase 실행.

필요 정보:

- Ubuntu 24.04 EC2
- Elastic IP
- EC2 SSH PEM 키
- `i15b110.p.ssafy.io` A 레코드
- 보안그룹 22, 80, 443 설정

설정:

1. EC2에 Elastic IP를 연결합니다.
2. 도메인 A 레코드를 Elastic IP로 설정합니다.
3. 22는 관리자 IP만, 80과 443은 서비스 접근을 허용합니다.
4. MySQL, Redis, Backend 포트는 공개하지 않습니다.

### 2.2 LiveKit Cloud

용도: WebRTC 영상·음성 통신.

가입 및 설정:

1. LiveKit Cloud 가입
2. 프로젝트 생성
3. Project Settings에서 Server URL, API Key, API Secret 발급
4. `/opt/camon/.env`의 `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`에 입력
5. Backend와 Frontend가 같은 LiveKit 프로젝트 URL을 사용하도록 설정

### 2.3 Hugging Face

용도: 물건 가져오기 AI의 SigLIP 2 모델 다운로드.

- AI 서버 최초 실행 시 모델을 자동 다운로드합니다.
- 공개 모델 사용 시 별도 Hugging Face Token이 필요하지 않습니다.
- 모델에 따라 약 800MB~4GB의 저장 공간이 필요합니다.
- GPU 환경은 CUDA 12.8 PyTorch wheel과 호환되어야 합니다.

### 2.4 Tailscale Funnel

용도: GPU 머신의 AI 서버 8100 포트를 공개 HTTPS 주소로 제공.

가입 및 설정:

1. Tailscale 계정 생성 및 GPU 머신 로그인
2. Tailscale 설치
3. AI 서버를 8100 포트로 실행
4. 다음 명령으로 Funnel 활성화

```bash
tailscale up
tailscale funnel 8100
```

5. 발급된 `https://...ts.net` 주소를 `AI_BASE_URL`에 입력
6. Frontend 이미지 재빌드

### 2.5 GitLab 및 Jenkins 연동

용도: Git 저장소 관리, CI 검사, `main` 운영 자동 배포.

필요 정보:

- GitLab 프로젝트 접근 계정 또는 Access Token
- Jenkins 로그인 계정
- Jenkins Credentials Store의 GitLab Credential

설정:

1. Jenkins에 GitLab Credential 등록
2. Multibranch Pipeline 생성
3. Repository URL에 GitLab 프로젝트 URL 입력
4. Script Path를 `Jenkinsfile`로 설정
5. GitLab Webhook 또는 Jenkins 저장소 scan 설정

Jenkins UI는 SSH 터널로 접속합니다.

```bash
ssh -i I15B110T.pem -o ExitOnForwardFailure=yes \
  -L 8080:localhost:8080 ubuntu@i15b110.p.ssafy.io
```

접속 주소:

```text
http://localhost:8080/login
```

### 2.6 Mattermost

용도: Jenkins 검사 및 배포 결과 알림.

필요 정보:

- Mattermost Server URL
- Team
- Channel
- Jenkins Mattermost Notification Plugin 설정

Jenkins 전역 설정에 Endpoint, Team, Channel을 등록합니다. Mattermost 알림 실패는 build와 deploy
결과를 변경하지 않습니다.

---

## 3. DB 덤프 파일 최신본

현재 `exec` 폴더는 단일 문서 조건에 따라 `README.md`만 포함하고 있으며 실제 운영 DB dump는
포함하지 않았습니다. 최신 dump 파일을 별도로 제출할 경우 파일명은 다음과 같이 사용합니다.

```text
camon_latest.sql
```

운영 EC2에서 최신 dump 생성:

```bash
sudo install -d -m 700 -o ubuntu -g ubuntu /opt/camon/backup

docker exec camon-mysql sh -c \
  'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" \
    --single-transaction --routines --triggers --events \
    --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
  > /opt/camon/backup/camon_latest.sql
```

파일 확인:

```bash
test -s /opt/camon/backup/camon_latest.sql
ls -lh /opt/camon/backup/camon_latest.sql
```

dump 복구:

```bash
cat camon_latest.sql | docker exec -i camon-mysql sh -c \
  'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
```

---

## 4. 시연 시나리오

### 4.1 시연 준비

1. 운영 URL `https://i15b110.p.ssafy.io`에 접속합니다.
2. 3~4대의 기기 또는 서로 다른 브라우저 프로필을 준비합니다.
3. 각 브라우저에서 카메라와 마이크 권한을 허용합니다.
4. 물건 가져오기용 `안경`, `가위`, `마우스`를 준비합니다.

### 4.2 시연 모드 활성화 및 방 생성

| 순서 | 화면 | 실행 및 클릭 위치 |
| --- | --- | --- |
| 1 | 첫 화면 | 배경의 **하트 말풍선 영역** 클릭 |
| 2 | 첫 화면 | `시연 모드 ON — 지금 만드는 방에 적용돼요` 문구 확인 |
| 3 | 첫 화면 중앙 | `방 만들기` 버튼 클릭 |
| 4 | 닉네임 입력 화면 | 닉네임 입력 후 입장 버튼 클릭 |
| 5 | 대기방 | 화면에 `시연 모드` 배지가 표시되는지 확인 |

시연 모드는 방을 생성하기 전에 활성화합니다. 이미 생성된 방에는 적용되지 않습니다.

### 4.3 참가자 입장 및 준비

| 순서 | 화면 | 실행 및 클릭 위치 |
| --- | --- | --- |
| 1 | 방장 대기방 | 오른쪽 방 코드 확인 및 참가자에게 공유 |
| 2 | 참가자 첫 화면 | `코드로 참여` 버튼 클릭 |
| 3 | 방 코드 입력 화면 | 전달받은 방 코드 입력 |
| 4 | 닉네임 입력 화면 | 서로 다른 닉네임 입력 후 입장 |
| 5 | 참가자 대기방 | 하단 `준비` 버튼 클릭 |
| 6 | 방장 대기방 | 참가자 전원의 준비 상태 확인 |
| 7 | 방장 게임 설정 | 게임 순서 설정 후 `게임 시작` 클릭 |

권장 게임 순서:

```text
닌자 → 물건 가져오기 → 몸으로 말해요 → 최종 결과
```

### 4.4 닌자: 손은 눈보다 빠르다

시연 모드 술법 순서:

1. `냥냥펀치`
2. `봉선화의 술`
3. `나선환`

| 순서 | 화면 | 실행 및 확인 내용 |
| --- | --- | --- |
| 1 | 카운트다운 | 3, 2, 1 카운트 확인 |
| 2 | 닌자 게임 | 중앙에 표시된 손동작 콤보 수행 |
| 3 | 공격권 획득자 화면 | 공격할 참가자 카드 클릭 |
| 4 | 공격 연출 | 암전 후 공격자와 대상 집중 화면 확인 |
| 5 | 공격 연출 | 술법 이펙트와 효과음 확인 |
| 6 | 게임 화면 | 대상 HP 0 및 탈락 처리 확인 |
| 7 | 다음 라운드 | 다음 카운트다운 시작 확인 |

시연 모드의 술법 데미지는 100입니다. 4인 기준 세 번 공격하면 최후 1인이 남아 종료됩니다.

### 4.5 물건 가져오기: 엄마! 내 물건 어딨어?

시연 모드 제시어 순서:

1. `안경`
2. `가위`
3. `마우스`

| 순서 | 화면 | 실행 및 확인 내용 |
| --- | --- | --- |
| 1 | 카운트다운 | 3, 2, 1 카운트 확인 |
| 2 | 물건 게임 | 화면 상단 제시어 확인 |
| 3 | 카메라 화면 | 실제 물건 전체를 카메라 중앙에 표시 |
| 4 | 내 화면 | AI 인식 성공 표시 확인 |
| 5 | 전체 화면 | 성공 참가자와 순위 상태가 전원에게 표시되는지 확인 |
| 6 | 점수 화면 | 서버 도착 순서에 따른 점수 반영 확인 |
| 7 | 다음 라운드 | 안경, 가위, 마우스 순서로 3라운드 진행 |

### 4.6 몸으로 말해요: 말하지 않아도 알아요

시연 모드 주제는 `동물`이며 제시어 순서는 다음과 같습니다.

1. `악어`
2. `알파카`
3. `코끼리`
4. `기린`

| 순서 | 화면 | 실행 및 확인 내용 |
| --- | --- | --- |
| 1 | 표현자 화면 | 제시어 확인 |
| 2 | 표현자 카메라 | 마이크를 사용하지 않고 몸으로 표현 |
| 3 | 다른 참가자 화면 | 오른쪽 채팅 입력창 클릭 |
| 4 | 채팅 입력창 | 정답 단어 입력 후 전송 버튼 클릭 |
| 5 | 게임 화면 | 정답 표시와 정답 효과음 확인 |
| 6 | 다음 표현자 | 표현자와 제시어가 다음 순서로 변경되는지 확인 |

### 4.7 중간 결과 및 최종 결과

| 순서 | 화면 | 실행 및 확인 내용 |
| --- | --- | --- |
| 1 | 중간 결과 | 참가자별 점수와 순위 확인 |
| 2 | 중간 결과 | 프로그레스 바와 공동 순위 표시 확인 |
| 3 | 중간 결과 | 다음 게임 이동 버튼 클릭 |
| 4 | 최종 결과 | 우승자 메인 화면 확인 |
| 5 | 최종 결과 | 참가자별 고유색 테두리와 최종 순위 확인 |
| 6 | 최종 결과 | 폭죽 이펙트 확인 |
