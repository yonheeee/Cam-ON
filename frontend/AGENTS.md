# 프론트엔드 작업 규칙 (Cam-ON)

> 프로젝트 전체 맥락/아키텍처/API 명세는 루트 `AGENTS.md`가 원본이다. 이 파일은 `frontend/`에서만
> 적용되는 UI/코드 규칙을 모은다. 루트 문서와 겹치는 내용은 여기에 복사하지 않는다.

## 기술 스택

- React 19 + TypeScript + Vite (컴포넌트는 전부 `.tsx`)
- 상태: Zustand, 서버 통신: 순수 `fetch`(현재), STOMP(`@stomp/stompjs`)로 실시간 이벤트 수신
- 화상통화: `livekit-client` + `@livekit/components-react`

## 폴더 구조

`src/features/<도메인>/` 아래에 `api/`(REST 클라이언트), `components/`, `hooks/`, `lib/`로 나눈다.
현재 도메인: `session`(닉네임 게스트 세션 발급), `room`(방 생성/입장/대기방), `webrtc`(화상통화),
`gesture`(손동작 인식), `ninja`(닌자 게임).

## UI 표시 규칙

- **참가자는 화면에 항상 닉네임으로만 표시한다.** `participantId`(게스트 UUID)/LiveKit identity 같은
  내부 식별자는 절대 화면에 노출하지 않는다 — API 호출·상태 매칭·React key 등 내부 로직용으로만 쓴다.
  (디버그용으로 id를 화면에 찍던 라인들은 제거했다. 새로 추가할 때도 id를 사용자에게 보여주지 말 것.)
  - 아직 닉네임 매핑이 없어 부득이 id 일부를 보여주던 자리(예: 게임 중 HP 목록)는 닉네임을 넘겨받는
    구조가 생기면 즉시 닉네임으로 교체한다 — id 노출은 임시이지 의도된 UI가 아니다.

## 서버 통신 규칙

- REST base URL은 `import.meta.env.VITE_API_BASE_URL ?? \`http://${window.location.hostname}:8080\``
  패턴을 쓴다(같은 머신 전제로 어느 기기에서 열든 맞는 주소를 가리키게). WS는 같은 방식에
  `VITE_WS_BASE_URL`과 `ws/wss` 프로토콜 분기.
- 백엔드 응답은 `{ data: ... }` envelope이라 `body.data`를 꺼내 쓴다. 에러는 `{ code, message }`.
- 모든 인증 필요한 요청에 `Authorization: Bearer <accessToken>` 헤더를 붙인다. accessToken은
  닉네임 세션 발급(`POST /api/sessions`) 응답으로 받는 게스트 JWT다(회원가입/로그인 없음).

## 배포

- 정적 파일 빌드(`npm run build`) → Docker(`frontend/Dockerfile`)로 dist만 뽑아 Caddy가 서빙.
- push만으로는 배포 안 됨(자동 CI 없음). EC2에서 `git pull` + `docker compose -f deploy/
  docker-compose.prod.yml up -d --build`를 수동 실행해야 반영된다. 배포 절차는 `deploy/README.md` 참고.
