# 작업 인수인계 (WebRTC + 손동작 인식 프로토타입)

> 다른 컴퓨터에서 이어서 작업하기 위한 문서. 프로젝트 전체 맥락은 루트의 `AGENTS.md`를 먼저 읽는다 —
> 이 문서는 그중 `frontend/`에서 진행한 WebRTC/손동작 인식 프로토타입 부분만 다룬다.

## 지금까지의 흐름 (왜 이렇게 됐는지)

1. `hand-gesture-recognition-mediapipe/`의 Python 손동작 인식 모델을 JS로 포팅해서 브라우저에서
   바로 돌릴 수 있는지 검토 → 가능하다고 판단 (모델이 Dense 3개짜리 초경량 MLP라 tfjs 없이도 포팅 가능).
2. 화상통화를 어떻게 구현할지 논의하다가, 사용자가 이미 기술 스택을 OpenVidu(LiveKit 기반)로
   정해뒀다는 걸 확인 → 직접 짜려던 P2P RTCPeerConnection 코드를 버리고 `livekit-client` +
   `@livekit/components-react`로 전환.
3. 로컬 테스트를 위해 Docker로 `livekit-server --dev` 띄움. 화질/딜레이 이슈를 여러 번 겪으며
   원인을 진단 (아래 "트러블슈팅" 참고).
4. 화상통화가 되는 걸 확인한 뒤, 포팅한 손동작 인식을 실제로 WebRTC 화면 안에서 실행 → 각자의
   판정 결과를 참가자 전원에게 공유하는 보드까지 구현.
5. `ham` 브랜치에 커밋 → PR #1 → `main`에 머지 완료.

## 확정된 기술 스택

프론트: Node 24 LTS, React 19.2.x, TypeScript 5.9.x, Vite 8.1.x, React Router 7.18.x,
Zustand 5.x, TanStack Query 5.x (Router/Query는 설치만 하고 아직 코드에서 안 씀).

미디어: OpenVidu 3.7.0(LiveKit 기반 배포판, 아직 실제로는 안 씀) / **livekit-client 2.20.x** /
**@livekit/components-react 2.9.x** — 로컬 테스트는 OpenVidu 없이 순정 LiveKit 서버로 진행 중.

## 다른 컴퓨터에서 이어서 하려면 (환경 셋업)

1. **Node 24 LTS** 설치 확인 (`node -v`). nvm-windows 쓴다면 관리자 권한 터미널에서
   `nvm install 24 && nvm use 24`.
2. **Docker Desktop** 설치 및 실행.
3. `cd frontend && npm install`
4. **LiveKit 로컬 dev 서버 실행** (아주 중요 — 아래 플래그 빠뜨리면 삽질함):
   ```
   docker run -d --rm --name livekit-dev \
     -p 7880:7880 -p 7881:7881 -p 7882:7882/udp \
     livekit/livekit-server --dev --bind 0.0.0.0 --node-ip 127.0.0.1
   ```
   - `--bind 0.0.0.0` 없으면 서버가 컨테이너 내부 `127.0.0.1`에만 바인딩돼서 Docker
     포트포워딩으로 호스트에서 아예 접속이 안 됨.
   - `--node-ip 127.0.0.1` 없으면 LiveKit이 STUN으로 "외부에서 보이는 IP"를 자동 추론해서
     미디어 후보 주소로 광고하는데, 로컬(같은 머신) 테스트에서는 이게 오히려 방해가 됨.
5. **개발 서버**: `npm run dev` (기본 `http://localhost:5173`)
6. **토큰 발급** (Spring 백엔드가 아직 없어서 로컬 전용 스크립트로 대체):
   ```
   node scripts/mint-dev-token.mjs <방이름> <참가자이름>
   ```
   출력된 JWT를 `http://localhost:5173`의 "Access Token" 칸에 붙여넣고 입장.
   devkey/secret은 LiveKit dev 모드의 플레이스홀더 키라 실서비스에 쓰면 안 됨 — 실제로는
   Spring이 LiveKit Server SDK(Java, 0.12.x)로 입장 시점에 토큰을 발급하는 구조로 갈 예정.

## 구현된 것 (`frontend/src/features/`)

### `webrtc/components/VideoCallRoom.tsx`
- `LiveKitRoom` + `VideoConference`(사전 제작 UI)로 화상통화 구현.
- `RoomOptions`에서 해상도/비트레이트/시뮬캐스트 레이어를 명시적으로 설정.
- **`adaptiveStream: true`로 되어 있음** — 껐다가(강제 최고화질) 4명을 한 기기에서 테스트할 때
  재생 지연이 2분 가까이 쌓이는 걸 확인하고 다시 켠 상태. 실제로 참가자마다 다른 기기를 쓰면
  이 문제가 없을 가능성이 높으니, 진짜 멀티 디바이스 테스트 후 다시 판단할 것.

### `gesture/` — 손동작 인식 JS 포팅
Python `hand-gesture-recognition-mediapipe/app.py` + `model/*.hdf5`를 그대로 포팅한 것.

- `lib/mlp.ts` — Keras Dense 3개짜리 MLP를 tfjs 없이 순수 행렬곱으로 구현 (Dropout은 추론 시
  no-op이라 생략).
- `models/*.json` — `keypoint_classifier.hdf5` / `point_history_classifier.hdf5`에서 Python
  h5py로 가중치만 직접 뽑아 JSON으로 export한 것 (TensorFlow 설치·변환 과정 없이 h5py만으로 끝냄).
- `lib/landmarkPreprocessing.ts` — `calc_landmark_list` / `pre_process_landmark` /
  `pre_process_point_history` 포팅.
- `lib/keypointClassifier.ts`, `lib/pointHistoryClassifier.ts` — 라벨 매핑 + 임계값(point
  history는 score 0.5 미만이면 'Stop' 처리) 포함.
- `hooks/useHandGestureRecognition.ts` — `@mediapipe/tasks-vision`의 HandLandmarker(브라우저
  WASM/GPU)로 랜드마크 추출 → 위 로직으로 판정, 손별(Left/Right) 히스토리 유지까지 app.py
  메인루프 포팅. CDN에서 WASM/모델을 fetch (`jsdelivr`, `storage.googleapis.com`) — 오프라인
  환경이면 안 됨.
- `components/GesturePanel.tsx` — 로컬 카메라 트랙을 별도 `<video>`에 붙여서(거울모드 없이 원본
  그대로) 스켈레톤 오버레이 + 판정 라벨 표시. 판정 결과를 LiveKit 데이터 채널로 브로드캐스트.
- `components/GestureBoard.tsx` + `store/gestureBoardStore.ts` (Zustand) — 참가자 전원의 판정
  결과를 한 화면에 모아 보여주는 공유 보드. 데이터 채널 수신으로 갱신.
- 인식 가능한 손모양 8종: Open/Close/Pointer/OK/V/Thumbs Up/Rock/Horse.
  움직임 4종: Stop/Clockwise/Counter Clockwise/Move.

## 트러블슈팅 히스토리 (다시 겪지 않도록)

1. **화질이 안 좋음** → 원인은 두 가지였음: (a) `adaptiveStream`이 렌더된 비디오 타일 크기에
   맞춰 자동으로 낮은 시뮬캐스트 레이어를 구독함, (b) 한 기기에 여러 참가자를 몰아넣고
   테스트하면 인코딩/디코딩이 CPU를 다 잡아먹어서 화질이 억눌림. 실제 멀티 디바이스에서는
   (b)가 사라짐.
2. **"딜레이가 몇 분씩 쌓인다"는 로그 발견** — `estimatedPropagationDelayNs`라는 LiveKit 서버
   디버그 로그 필드가 6분까지 찍힌 적 있음. RTT/패킷손실은 멀쩡한데 이 필드만 폭주 → **이
   필드는 신뢰할 수 없는 걸로 결론** (사용자 실제 체감 딜레이는 1초 수준이었음). 앞으로 지연
   측정은 이 로그 필드 말고 `chrome://webrtc-internals`나 실제 체감을 기준으로 할 것.
3. **데이터 채널로 "손동작 결과 브로드캐스트" 구현 중 `UnexpectedConnectionState: PC manager is
   closed` 에러** — 원인은 인식 루프(초당 30~60회)마다 전송을 재시도하면서 데이터 채널
   negotiate가 폭주해서 채널이 아예 못 열림. **0.5초 간격 타이머로 전송 빈도 제한**해서 해결.
4. **그런데도 늦게 들어온 참가자가 상대방 결과를 영원히 못 받는 문제** — 값이 바뀔 때만
   재전송하는 dedup 로직 때문에, 상대방 구독 채널이 열리기 전에 보낸 메시지가 유실되면 그
   뒤로 값이 안 바뀌는 한(예: 계속 손 미인식) 재전송이 없었음. **dedup을 없애고 0.5초마다
   무조건 재전송**하도록 변경해서 해결 (payload가 60바이트 수준이라 비용 문제 없음).
5. **`msg.from`이 `undefined`로 오는 경우 있음** — LiveKit 데이터 메시지의 발신자 메타데이터에
   기대지 않고, payload 안에 직접 `identity` 필드를 넣어서 해결.
6. **Docker Desktop이 유휴 상태에서 꺼짐** — 재부팅/장시간 유휴 후에는 `docker ps`가
   `dockerDesktopLinuxEngine` 파이프 에러를 내는데, Docker Desktop 앱을 다시 켜면 됨.

## 아직 안 된 것 / 다음 단계

- **React Router, TanStack Query는 설치만 되어 있고 실제로 안 씀** — 로비/대기방/게임방 라우팅,
  방 생성·입장 REST 연동은 다음 단계.
- **토큰 발급이 완전히 로컬 임시 스크립트(`scripts/mint-dev-token.mjs`)** — Spring 백엔드가
  생기면 LiveKit Server SDK(Java)로 대체해야 함. 이 스크립트는 그때 삭제 대상.
- **손동작 인식이 게임 로직과 연결 안 됨** — 지금은 순수 인식+표시만 됨. 닌자 게임의
  "콤보 완성 판정", "공격권 부여" 같은 룰은 아직 없음.
- **물건 가져오기 게임의 객체 인식은 별도 검토 필요** — 손동작 분류기보다 훨씬 무거운 모델이
  필요해서 AI 서버(Python)에 남기기로 잠정 결론 (AGENTS.md의 API 명세에도 `/ai/object-detection`
  등이 AI 서버 경유로 명시돼 있음).
- **판정 신뢰성(치팅 방지) 미해결** — 지금처럼 손동작 인식을 클라이언트에서만 하면, 경쟁
  게임(닌자)에서는 조작 여지가 있음. 서버가 최종 검증하는 방식으로 갈지 결정 필요.

## Git 상태

- `ham` 브랜치의 작업이 PR #1로 `main`에 머지됨: https://github.com/yanghaemi/temporary-plAIground/pull/1
- 로컬 `ham` 브랜치에 `git stash`가 하나 남아있을 수 있음 (`data-architecture-erd.html` 관련,
  이 작업과 무관한 동시 편집 중이던 변경사항 — 확인 후 필요없으면 정리).
