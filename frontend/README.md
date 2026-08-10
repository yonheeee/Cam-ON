# Cam-ON Frontend

> 카메라와 마이크를 게임 입력으로 사용하는 2~4인 실시간 화상 파티게임 플랫폼의 프론트엔드다.
> 메인 화면부터 대기방, 세 게임, 중간 결과와 최종 결과까지 **Pixel Arcade Plaza** 테마로 구성한다.

## 주요 기능

- 닉네임 기반 게스트 세션 발급
- 방 생성, 참여 코드·초대 링크 입장, 대기방 관리
- LiveKit 기반 2~4인 화상통화
- STOMP 기반 방·게임·세트 진행 상태 실시간 동기화
- 참가자별 음량 조절, 배경음악·효과음 설정
- 게임 구성과 최대 7세트 코스 진행
- 세 가지 미니게임
  - `엄마! 내 물건 어디있어?!`: 카메라로 제시된 물건 찾기
  - `손은 눈보다 빠르다면서요`: 손동작 콤보와 HP 기반 생존 게임
  - `말하지 않아도 알아요`: 제시어를 몸으로 표현하고 정답 맞히기
- 세트 중간 결과, 누적 순위, 최종 결과
- 개발 전용 결과 화면 QA 경로

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| UI | React 19.2, TypeScript 5.9, Vite 8.1 |
| 라우팅 | React Router 7 |
| 상태 | Zustand |
| REST / 실시간 | Fetch API, STOMP |
| 화상통화 | LiveKit |
| 비전·렌더링 | MediaPipe Tasks Vision, PixiJS |
| 코드 검사 | Oxlint, TypeScript build |

## 시작하기

### 1. 요구 환경

- Docker 빌드 기준 Node.js 24 권장
- npm
- 실행 중인 Cam-ON 백엔드
- 백엔드와 같은 LiveKit 프로젝트의 서버 URL
- 물건 찾기 인식을 확인하려면 별도 AI 서버

### 2. 설치

```bash
cd frontend
npm ci
```

### 3. 환경 변수

`.env.example`을 `.env`로 복사하고 로컬 환경에 맞게 값을 채운다.

```bash
cp .env.example .env
```

Windows PowerShell에서는 다음과 같이 복사할 수 있다.

```powershell
Copy-Item .env.example .env
```

| 변수 | 필수 여부 | 설명 |
| --- | --- | --- |
| `VITE_LIVEKIT_URL` | 필수 | 백엔드가 토큰을 서명하는 프로젝트와 같은 LiveKit 서버 URL |
| `VITE_API_BASE_URL` | 선택 | REST 서버. 없으면 `http://<현재 호스트>:8080` |
| `VITE_WS_BASE_URL` | 선택 | STOMP 서버. 없으면 현재 페이지에 맞춰 `ws/wss://<현재 호스트>:8080` |
| `VITE_AI_BASE_URL` | 물건 인식 시 필요 | AI 서버. 없으면 `http://<현재 호스트>:8100` |
| `VITE_APP_VERSION` | 선택 | 분석 이벤트에 기록할 앱 버전. 기본값 `local` |
| `VITE_EXPERIMENT_VERSION` | 선택 | 분석 이벤트 실험 버전. 기본값 `baseline` |

`VITE_*` 값은 브라우저 번들에 포함된다. API key, secret, 개인 키 같은 비밀값은 넣지 않는다.

### 4. 개발 서버

```bash
npm run dev
```

기본 주소는 [http://localhost:5173](http://localhost:5173)이다. 다른 기기에서 접속하려면 Vite를
호스트 공개 옵션으로 실행하고 방화벽과 백엔드 CORS 설정도 함께 확인한다.

```bash
npm run dev -- --host 0.0.0.0
```

## 명령어

| 명령어 | 설명 |
| --- | --- |
| `npm run dev` | Vite 개발 서버 실행 |
| `npm run lint` | Oxlint 검사 |
| `npm run build` | TypeScript 검사 후 운영 빌드 |
| `npm run preview` | 빌드 결과 로컬 확인 |

변경을 마치기 전 최소 `npm run lint`와 `npm run build`를 통과시킨다.

## 화면 경로

| 경로 | 설명 |
| --- | --- |
| `/` | 메인 화면 |
| `/join` | 참여 코드 입력 모달 |
| `/nickname` | 닉네임 입력 모달 |
| `/rooms/join?code=XXXXXX` | 초대 링크 진입점 |
| `/rooms/:roomId` | 대기방, 게임 구성, 게임, 결과 화면 |
| `/dev/set-result` | 중간 결과 상태 확인(개발 환경 전용) |
| `/dev/course-result` | 최종 결과 상태 확인(개발 환경 전용) |

`/players`는 이전 북마크 호환을 위해 닉네임 입력 흐름으로 이동한다. 존재하지 않는 경로는 메인 화면으로
돌아간다.

## 폴더 구조

```text
src/
├─ features/
│  ├─ landing/      # 메인 화면과 입장 흐름
│  ├─ session/      # 게스트 세션
│  ├─ room/         # 방 생성·입장·대기방·환경 설정
│  ├─ webrtc/       # LiveKit 영상·음성
│  ├─ chat/         # 대기방 채팅
│  ├─ sound/        # 배경음악·효과음
│  ├─ fetch/        # 엄마! 내 물건 어디있어?!
│  ├─ ninja/        # 손은 눈보다 빠르다면서요
│  ├─ charades/     # 말하지 않아도 알아요
│  ├─ course/       # 게임 구성·세트 진행·결과
│  ├─ gesture/      # 손동작 인식
│  ├─ analytics/    # 사용 이벤트
│  ├─ system/       # 공통 시스템 UI
│  └─ demo/         # 개발 확인 기능
├─ assets/          # 번들에 포함되는 정적 자산
├─ theme.css        # Pixel Arcade Plaza 토큰과 공통 컴포넌트
├─ App.tsx          # 라우팅
└─ main.tsx         # 앱 진입점

public/assets/      # 배경·결과·사운드 등 원본 정적 자산
```

도메인 코드는 `src/features/<도메인>/` 안에서 `api`, `components`, `hooks`, `lib`, `store`로 나눈다.
둘 이상의 화면에서 실제로 재사용하는 기능만 공통 영역으로 올린다.

## 핵심 UI 규칙

- 참가자는 닉네임으로 표시하고 UUID나 LiveKit identity를 화면에 노출하지 않는다.
- 참가자 색상은 대기방 입장 순서로 배정하고 게임·중간 결과·최종 결과까지 유지한다.
- 발화 중 외곽선도 해당 참가자 색상의 네온 효과를 사용한다.
- 전체 사운드 패널에는 배경음악과 효과음만 두고, 참가자 음량은 각 영상 카드에서 조절한다.
- 공통 상단 바, 방 나가기 확인창, 연결 상태 팝업을 화면마다 다시 구현하지 않는다.
- 기본 글꼴은 Mona12, 대체 글꼴은 Galmuri 계열이다.

세부 토큰과 화면별 기준은 [`design.md`](./design.md), 작업 규칙은 [`AGENTS.md`](./AGENTS.md)를 따른다.

## 개발 확인

- 결과 화면을 반복해서 게임하지 않고 확인하려면 개발 서버에서 `/dev/set-result`,
  `/dev/course-result`를 사용한다.
- QA 화면은 실제 화면 컴포넌트를 사용하므로 상태 추가 시 운영 화면과 함께 갱신한다.
- 공통 상단 바, 사운드, 참가자 카드 변경은 대기방과 세 게임에서 모두 확인한다.
- 기본 화면 외에 2·3·4인, 빈 슬롯, 동점, 마지막 세트, 연결 끊김 상태를 함께 확인한다.

## 빌드와 배포

- `npm run build`가 `dist/`를 만든다.
- Docker 빌드 단계에서 `VITE_*` 환경 변수가 번들에 포함된다. 런타임 환경 변수로 바뀌지 않는다.
- Jenkins는 `develop`, `main`, 프론트엔드 feature 브랜치에서 `npm ci`, lint, build를 실행한다.
- `develop`은 통합 이미지 검증 대상이고 운영 배포는 `main` 파이프라인에서 수행한다.
- 운영 값과 장애 시 수동 절차는 루트 `Jenkinsfile`과 `deploy/README.md`를 기준으로 한다.

## 문제 해결

### 서버에 연결하지 못했어요

- 백엔드가 `8080` 포트에서 실행 중인지 확인한다.
- 백엔드가 다른 기기라면 `VITE_API_BASE_URL`, `VITE_WS_BASE_URL`을 명시한다.
- 환경 변수를 바꾼 뒤 Vite 개발 서버를 다시 시작한다.
- 초대 링크는 프론트엔드 주소와 방 코드가 올바른지 확인한다.

### 화상 화면이 연결되지 않아요

- `VITE_LIVEKIT_URL`이 비어 있지 않은지 확인한다.
- 백엔드가 토큰을 서명한 LiveKit 프로젝트와 같은 URL인지 확인한다.
- 브라우저 카메라·마이크 권한과 HTTPS 또는 localhost 조건을 확인한다.

### 물건 인식이 동작하지 않아요

- AI 서버가 실행 중이고 `VITE_AI_BASE_URL`에서 접근 가능한지 확인한다.
- HTTPS 페이지에서 HTTP AI 서버를 호출하면 mixed content로 차단된다. 배포 환경은 HTTPS 주소를 사용한다.
- 브라우저 개발자 도구에서 요청 실패와 카메라 권한을 함께 확인한다.
