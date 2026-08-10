# 프론트엔드 작업 규칙 (Cam-ON)

> 프로젝트 전체 맥락/아키텍처/API 명세는 루트 `AGENTS.md`가 원본이다. 이 파일은 `frontend/`에서만
> 적용되는 UI/코드 규칙을 모은다. 루트 문서와 겹치는 내용은 여기에 복사하지 않는다.

## 기술 스택

- React 19.2 + TypeScript 5.9 + Vite 8.1 (컴포넌트는 `.tsx`)
- 라우팅: React Router 7, 상태: Zustand (TanStack Query는 설치되어 있으나 현재 사용하지 않음)
- 서버 통신: `fetch`, STOMP(`@stomp/stompjs`) 실시간 이벤트
- 화상통화: `livekit-client` + `@livekit/components-react`
- 비전/게임 렌더링: MediaPipe Tasks Vision, PixiJS

## 폴더 구조

`src/features/<도메인>/` 아래에 필요에 따라 `api/`, `components/`, `hooks/`, `lib/`, `store/`를 둔다.

- `landing`: 메인 화면과 입장 흐름
- `session`: 닉네임 기반 게스트 세션 발급
- `room`: 방 생성/입장/대기방/환경 설정
- `webrtc`: LiveKit 참가자 영상·음성과 참가자별 음량
- `chat`: 대기방 채팅
- `sound`: 배경음악·효과음 공통 설정
- `fetch`: 엄마! 내 물건 어디있어?!
- `ninja`: 손은 눈보다 빠르다면서요
- `charades`: 말하지 않아도 알아요
- `course`: 세트 진행, 중간 결과, 최종 결과
- `gesture`: 손동작 인식
- `analytics`, `system`, `demo`: 분석·공통 시스템·개발 확인 화면

도메인 전용 코드는 해당 feature 안에 두고, 둘 이상의 화면에서 재사용하는 기능만 공통 영역으로 올린다.

## UI 표시 규칙

- **참가자는 화면에 항상 닉네임으로만 표시한다.** `participantId`(게스트 UUID), LiveKit identity 같은
  내부 식별자는 API 호출·상태 매칭·React key 등 내부 로직용으로만 사용한다.
- 참가자 색상은 **대기방 입장 순서로 한 번 배정한 뒤 게임 화면, 중간 결과, 최종 결과까지 유지한다.**
  순위가 바뀌어도 참가자 색상을 등수 색으로 덮어쓰지 않는다.
- 발화 중 테두리는 공통 네온 초록색이 아니라 해당 참가자의 색상을 밝힌 네온 효과로 표시한다.
  발화 배지나 별도 중앙 장식은 추가하지 않는다.
- 일반 동작은 주황색, 정보/선택은 아케이드 블루, 파괴적 동작과 오류는 코럴 계열을 우선한다.
  참가자 4색은 개인 식별용이므로 일반 CTA 색상으로 사용하지 않는다.
- 화면에 보이는 한글·영문·숫자는 Mona12를 기본으로 하고 Galmuri 계열을 대체 글꼴로 둔다.
- 공통 상단 바, 사운드 패널, 방 나가기 확인창은 화면별로 다시 만들지 말고 공용 컴포넌트를 사용한다.
- 전체 사운드 패널에는 배경음악과 효과음만 둔다. 참가자 음량은 각 참가자 카드에서 로컬로 조절한다.

## 서버 통신 규칙

- REST base URL은 `VITE_API_BASE_URL`을 우선하고, 없으면 `http://${window.location.hostname}:8080`을
  사용한다. WS도 `VITE_WS_BASE_URL`을 우선하고 현재 페이지 프로토콜에 따라 `ws/wss`를 선택한다.
- 백엔드 응답은 `{ data: ... }` envelope이면 `body.data`를 꺼내 쓴다. 에러는 `{ code, message }`를 따른다.
- 인증이 필요한 요청에는 `Authorization: Bearer <accessToken>`을 붙인다. accessToken은
  닉네임 세션 발급(`POST /api/sessions`) 응답으로 받는 게스트 JWT다(회원가입/로그인 없음).
- 실시간 이벤트를 화면 로컬 상태에 반영할 때 참가자 식별자, 방 코드, 현재 세트/라운드를 함께 확인한다.
  이전 게임이나 이전 방의 이벤트가 현재 화면을 덮지 않도록 한다.

## 개발 확인 화면

- `/dev/set-result`, `/dev/course-result` 같은 QA 경로는 `import.meta.env.DEV`에서만 노출한다.
- QA 화면은 실제 화면 컴포넌트와 동일한 데이터 구조를 사용한다. 별도 목업 구현을 복제해 두지 않는다.
- 새 상태를 추가할 때는 정상 상태뿐 아니라 동점, 2~4인, 마지막 세트, 연결 끊김, 빈 슬롯을 함께 확인한다.

## 접근성과 반응형

- 아이콘 버튼에는 `aria-label` 또는 동일한 접근성 이름을 제공한다.
- `:focus-visible` 상태를 없애지 않는다. 색상만으로 상태를 전달해야 할 때는 텍스트나 아이콘을 병행한다.
- `prefers-reduced-motion`에서는 장식 애니메이션과 이동 효과를 줄인다.
- 2~4인 레이아웃은 참가자 수에 맞는 변형을 사용하고, 고정 화면 크기를 가정한 절대 위치 남용을 피한다.

## 검증

- 프론트엔드 변경 후 최소 `npm run lint`와 `npm run build`를 실행한다.
- UI 변경은 대상 화면과 관련 QA 경로를 함께 확인한다. 공용 컴포넌트 변경은 대기방과 세 게임 화면을 모두 본다.

## 배포

- 정적 파일 빌드(`npm run build`) 결과를 Docker 이미지로 만들고 Caddy가 서빙한다.
- Jenkins가 `develop`, `main`, 프론트엔드 feature 브랜치에서 프론트엔드 CI(`npm ci`, lint, build)를 수행한다.
- `develop`은 통합 이미지 검증 대상이고, 운영 배포는 `main` 파이프라인에서 자동으로 수행한다.
- Vite의 `VITE_*` 값은 이미지 빌드 시점에 번들에 포함된다. 운영 값과 수동 복구 절차는
  루트 `Jenkinsfile`과 `deploy/README.md`를 기준으로 한다.
