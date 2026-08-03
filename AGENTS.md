# Cam-ON — 프로젝트 개요

> 이 문서는 새 세션(Claude Code, Codex 등)이 프로젝트 맥락을 별도 설명 없이 파악하기 위한
> 진입점이다. `CLAUDE.md`는 이 파일을 `@AGENTS.md`로 임포트하므로, 이 파일이 유일한 원본이다.
> 내용이 바뀌면 이 파일만 수정하면 된다.

## 프로젝트가 뭔가

**Cam-ON**은 WebRTC 기반 실시간 화상통화와 MediaPipe 포즈/손동작 인식을 결합해, 키보드 입력으로는
구현 불가능한 신체 기반 상호작용 미니게임 3종(닌자, 물건 가져오기, 몸으로 말해요)을 제공하는 파티게임
플랫폼이다. 회원가입 없이 코드/링크로 방에 참여하는 임시 세션 기반 서비스다.

**현재 상태(2026-07-29 기준): 구현 단계.** `backend/`(Spring)와 `frontend/`(React+Vite)가 실제로
동작한다 — 방/세션/준비상태, 닌자·몸으로 말해요 게임 진행, 대기방 코스 설정과 코스대로 이어지는
게임 진행(코스 종료 시 종합 결과)까지 구현됨. 물건 가져오기 백엔드와 AI 서버 연동은 미구현.
`hand-gesture-recognition-mediapipe/`는 손동작 인식 프로토타입(Kazuhito00 포크 + 닌자용 스킬
이펙트 `utils/skill_effect.py`)으로, 로컬 웹캠 데모(`app.py`)로만 동작한다.

## 시스템 아키텍처 (3개 서버, 서로 직접 통신 안 함)

- **Frontend**: 브라우저. Spring 서버·AI 서버 각각에 직접 연결하고 중간에서 데이터를 이어준다.
  방 생성/입장, 카메라·마이크 권한, 준비 상태 체크, 참가자 간 WebRTC 화상통화, AI 서버에 인식
  요청(프레임/랜드마크) 후 판정 응답 수신, Spring 서버와 STOMP로 방/게임 상태 동기화. **AI 판정
  결과를 Spring에 전달해 Redis에 반영시키는 것이 두 백엔드 사이의 유일한 다리** — Frontend가 중계한다.
- **Spring 서버 (Java)**: 게임 로직·상태 관리. 방 생성/입장/퇴장, 방장 위임, 준비 상태 판정, STOMP로
  실시간 브로드캐스트, Redis 읽기/쓰기. 인식 로직엔 전혀 관여하지 않고 AI 서버를 직접 호출하지 않는다.
- **AI 서버 (Python)**: 손동작·객체 인식 전용 stateless 추론 서비스. MediaPipe로 랜드마크 추출 →
  분류기로 판정 → `detected_value`/`confidence`/`is_valid`를 Frontend에 응답. MySQL의 `games`,
  `missions`를 직접 조회(거의 안 바뀌는 정적 데이터라 Spring을 거치지 않음). 게임 로직·방 상태는 모른다.
- **상태 변경/전파 공통 정책**: 상태 변경 요청은 REST API, 변경 전파는 WebSocket(STOMP) 이벤트로 한다.

상세 다이어그램(각 서버 책임, MySQL/Redis 데이터 흐름 화살표 포함)은 [data-architecture-erd.html](data-architecture-erd.html)에 있다 — 로컬에서 브라우저로 열어서 확인.

## 데이터 구조 요약

- **MySQL(영구 저장, 거의 안 바뀜)**: `games`(게임 종류/인원·라운드 범위), `missions`(게임별 제시어/정답),
  닌자 전용 `gesture`/`skill`/`skill_gesture`/`effect`(손동작 조합 → 스킬 → 파티클 이펙트 매핑).
  원래 스키마에 있던 `users`/`rooms`/`room_participants`/`game_sessions`/`rounds`/`round_results`/
  `ai_judgement_logs` 7개 테이블은 전부 Redis로 대체되어 MySQL에서 빠졌다.
- **Redis(방 생명주기 데이터, 방 종료 시 전부 삭제)**: `room:{code}` 방 상태, `room:{code}:course:{idx}`
  대기방에서 미리 확정한 세트 큐 — 항목 하나 = 게임 1세트, 최대 7세트, 몸으로 말해요는 주제 포함. `room:{code}:participants` 입장 순서(ZSET, 방장 연쇄 위임에
  사용), `room:{code}:participant:{token}` 참가자 상태(닉네임/연결상태/준비여부), `...:session:{seq}`
  진행 중 게임, `...:round:{n}` 라운드, `...:results`/`...:totals`/`room:{code}:course:totals` 점수 누적,
  `session:{token}:alive`(TTL 15초 하트비트, 방장 위임/강제퇴장 트리거). 게임별 전용 키(닌자
  `alive_players`/`eliminated`/`round:{n}:attack`/`player_hp`, 물건가져오기 `round:{n}:submissions`,
  몸으로말해요 `round:{n}:guesses`)도 있다.
- 필드 단위 상세 스키마와 설계 이유(왜 콤보를 VARCHAR 하나에 안 넣는지 등)는 반드시
  [data-architecture-erd.html](data-architecture-erd.html)의 "데이터 구조 상세" 섹션에서 확인한다 — 아래
  요약보다 그쪽이 항상 최신 원본이다.

## 공통 기능 요구사항

- **계정/세션**: 회원가입 없음, 코드/링크로 참여, 방 안에서만 유일하면 되는 닉네임(중복 시 재입력 요구),
  게스트 토큰(UUID) 발급 → sessionStorage 저장 → STOMP 세션 매핑. 방 종료 시 관련 데이터 즉시 삭제.
- **방장**: 방 생성자가 방장. 재접속 유예(15초) 초과로 강제 퇴장되면 입장 순서상 다음 참가자에게
  연쇄 위임(위임 대상도 끊겨 있으면 그다음으로).
- **카메라/권한**: 입장 시점 권한 거부 → 입장 자체 차단. 게임 도중 인식 실패는 해당 라운드만
  탈락/0점 처리하고 방은 유지.
- **네트워크/재접속**: 연결 끊김 15초간 "연결 끊김" 화면 표시, 15초 내 미복귀 시 강제 퇴장. 카메라/마이크
  테스트는 게임 시작 직전에만 하므로, 강제 퇴장자는 게임 진행 중이 아니면 다음 게임 시작 전까지만
  재입장 가능(게임 진행 중 퇴장 시 해당 게임 종료까지 재입장 불가). 퇴장이 감지되는 두 경로와
  그게 방/진행 중인 게임에 반영되는 흐름은 [backend/docs/participant-leave-flow.md](backend/docs/participant-leave-flow.md)에
  정리돼 있다 — 새 게임을 추가하면 `GameParticipantLeaveHandler`도 같이 구현해야 한다.
- **준비 상태**: 준비 완료 = 카메라 권한 완료 AND 인식 테스트 통과. 전원 준비 완료여야 게임 시작 가능.

## 게임 3종 요약

코스는 "세트" 큐다 — 항목 하나 = 게임 1세트(라운드 수는 게임별 고정), 같은 게임을 반복해 담아
세트 수를 표현하고(닌자 2세트 = [닌자, 닌자]), 게임을 다 합쳐 최대 7세트.

| 게임 | 핵심 규칙 | 인식 대상 | 승리조건 | 인원 | 1세트 |
| --- | --- | --- | --- | --- | --- |
| 물건 가져오기 「엄마! 내 물건 어딨어?」 | 제시어 물건을 시간 내 카메라 앞에 가져옴 | 손에 든 물체 | 가장 빨리 가져온 순서대로 1~4위, 시간초과 시 전원 0점 | 2~4명 | 5라운드(물건 5개) |
| 닌자 「손은 눈보다 빠르다」 | 교환마다 손동작(콤보) 제시 → 가장 빨리 완성한 사람이 공격권 획득 → 대상 지정 → 스킬 데미지로 HP 차감 | 손동작(손동작 조합 = 스킬) | 최후 1인 생존 시 세트 종료, 늦게 탈락한 순서대로 순위 | 2~4명 | 최후 1인이 남는 한 판 |
| 몸으로 말해요 「말하지 않아도 알아요」 | 선택한 주제의 제시어를 표현자가 마이크 없이 행동으로 설명 → 다른 참가자가 채팅으로 정답 시도. 턴당 1분. 표현자 연결 끊기면 해당 턴은 무효 처리되고 다음 표현자가 이어받음 | 없음(채팅 텍스트 매칭) | 정답 시 표현자·최초 정답자 각 1점, 세트 종료 시 세트 점수·누적 점수와 순위 공개 | 3~4명 | 전원이 한 번씩 표현 |

세부 규칙(예외 처리, 코스 전체 누적 점수 계산 등)은 [hand-gesture-recognition-mediapipe/요구사항명세서.md](hand-gesture-recognition-mediapipe/요구사항명세서.md)가 정확한 원본이다(위치는 어색하지만 내용은 최신·정확함).

## API 명세

- 정확한 원본: [API 명세서 39e9d5f88b958016a4a7eea704dc19d6.csv](API%20명세서%2039e9d5f88b958016a4a7eea704dc19d6.csv)
  (기능/분류별 정렬본과 `_all.csv`는 컬럼 순서만 다른 동일 데이터)와
  [API 명세서 39e9d5f88b95804eb7ebf70c7f3268a5.md](API%20명세서%2039e9d5f88b95804eb7ebf70c7f3268a5.md)(공통 정책).
- 공통 정책: 회원가입/로그인 없음, 사용자 식별은 닉네임 입력 후 발급되는 `accessToken`, 백엔드-AI 서버는
  직접 연결 없음(Frontend가 매개), 상태 변경은 API·전파는 WebSocket Event.
- `기능` 컬럼은 domain 단위(세션/방/게임 공통/물건가져오기/몸으로말해요/손동작 스킬 배틀) 6그룹으로 정리되어 있다 —
  REST냐 WS냐로 나누지 않는다는 백엔드 패키지 원칙(아래 참고)을 API 명세서 자체에도 적용한 것.
- URL 프리픽스로 어느 서버가 처리하는지 구분: Spring은 `/api/...`(REST, 버전 프리픽스 없음)와
  `/ws/rooms/{roomId}`(STOMP, WebRTC signaling·멤버/준비상태 전파·몸으로말해요 채팅), AI 서버는
  `/ai/games/{gameId}/...`(REST, 게임별 인식 요청·미션 조회)와 `/ws/ai/games/{gameId}`(연속 프레임 전송·비동기 판정 결과).
  실제 AI 인식이 없는 몸으로말해요는 전부 Spring 쪽(`/api/...`, `/ws/rooms/{roomId}`)에서 처리한다.
- "라운드 시작 시 이번 라운드 콘텐츠가 뭔지" 알려주는 것들(물건 제시/제시어/손동작 시퀀스)은 서버가 미는(push) WS
  이벤트가 아니라 프론트가 필요할 때 조회하는 REST GET이다 — 여러 명에게 동시에 실시간으로 밀어줘야 하는 것만 WS를 쓴다.

## 깨진/신뢰할 수 없는 파일 (읽지 말 것)

- `기능 명세서.html`, `요구사항 명세서.html`: Google Sheets 내보내기 과정에서 한글 텍스트가
  U+FFFD(치환 문자)로 손상된 채 저장되어 있다. HTML의 표 구조(ID 체계: `COM01_ACC01`,
  `GAME01_FLOW01` 등)만 읽을 수 있고 내용은 복구 불가능하다. 같은 내용의 정확한 원본은 위
  "게임 3종 요약"/"공통 기능 요구사항" 섹션과 `요구사항명세서.md`, API 명세 CSV/MD를 대신 참고한다.

---

# Git 브랜치/머지 워크플로우

**공유 브랜치(`main`, `develop`, `develop-backend`, `develop-frontend`)에는 절대 직접 push 하지
않는다.** 모든 변경은 작업 브랜치(`feature/*`, `fix/*` 등)에서 커밋·push 한 뒤 **MR(Merge
Request)로만** 반영한다. 팀원 전원과 AI 에이전트가 이 규칙을 따른다 — "develop에 올려줘",
"머지해줘" 같은 요청도 **공유 브랜치 직접 push가 아니라 "작업 브랜치 push → MR"** 을 의미한다.

## 브랜치 모델

```
feature|fix/backend/*   ──MR──▶ develop-backend
feature|fix/frontend/*  ──MR──▶ develop-frontend
        develop-backend + develop-frontend ──통합──▶ develop
        develop ──(테스트 통과 후 MR)──▶ main ──▶ 자동 배포(EC2)
```

- FE와 BE가 한 몸으로 엮인 변경(예: 새 REST 엔드포인트 + 그걸 부르는 프론트)은 `develop`에서
  작업 브랜치를 따서 `develop`으로 MR 한다 — 어느 한쪽 통합 브랜치엔 짝이 없어서 반쪽만
  올라가거나 배포가 깨질 수 있다.
- 작업 브랜치를 **어느 브랜치에서 분기했는지**가 곧 MR 대상이다. 엉뚱한 브랜치에서 따면 그
  브랜치의 무관한 커밋들이 MR에 딸려 들어간다(분기 지점을 맞춰서 딸 것).

## 배포 트리거 (직접 push가 특히 위험한 이유)

- **`main`**: Jenkins가 EC2에 자동 배포. protected 브랜치라 직접 push가 막힐 수 있음(MR 필수).
- `develop` / `develop-backend` / `develop-frontend`: 자동 배포 없이 통합·검증에만 사용한다.
  공유 브랜치이므로 변경은 MR로만 반영한다.

---

# 백엔드 패키지 구조 강령 (Spring)

이 문서는 Spring 백엔드의 패키지 구조 원칙을 정리한 것이다. 팀원 전원과 AI 에이전트는 새 코드를
작성할 때 이 구조를 따른다. 백엔드 프로젝트 루트가 별도로 생성되면 이 파일을 그 루트로 옮긴다.

## 최상위 구조

```
src/main/java/.../
  domain/    기능(도메인) 단위 패키지. room, participant, course, game/ninja, game/fetch,
             game/charades, score, media 등
  global/    도메인에 속하지 않는 공통 인프라. config, security, ws, apiresponse, exception 등
```

기준: **"REST냐 WebSocket이냐"는 패키지를 나누는 기준이 아니다.** 같은 기능(예: room)이면
REST든 WS든 같은 `domain/room` 아래에 둔다. 반대로 "이 도메인만의 로직이냐, 모든 도메인이
똑같이 쓰는 배관(plumbing)이냐"가 domain vs global을 가르는 기준이다.

## 도메인 패키지 표준 하위 구조

```
domain/room/
  controller/   REST 엔드포인트. 요청을 받아 service에 위임만 함 (얇게 유지)
  service/      상태 변경 로직 (Redis/MySQL 갱신) + 변경 직후 ws 쪽 호출
  ws/           이 도메인이 발행하는 WS 이벤트 + (있다면) 이 도메인이 수신하는 @MessageMapping
  repository/   데이터 접근
  dto/          요청/응답, WS payload DTO
```

## global/ws vs domain/*/ws 역할 구분

- `global/ws/WebSocketConfig` — STOMP 브로커 설정(`@EnableWebSocketMessageBroker`), 핸드셰이크 시
  게스트 토큰 인증 인터셉터. 모든 도메인이 공유하는 순수 전송 계층 설정.
- `global/ws/StompBroadcaster` — `SimpMessagingTemplate`을 얇게 감싼 범용 헬퍼(`send(destination, payload)`
  정도). 이벤트 이름이나 도메인 로직을 모른다.
- `domain/room/ws/RoomEventPublisher` — room 도메인 전용. 이벤트별 payload DTO를 만들고
  `StompBroadcaster`에 destination(`/topic/rooms/{roomCode}` 등)과 함께 위임한다.
- `domain/room/ws/RoomStompController` — (필요 시) 클라이언트가 WS로 직접 보내는 메시지를
  받는 `@MessageMapping` 핸들러. 이것도 room 도메인 안에 둔다.

**금지**: `domain/ws/room` 처럼 도메인 트리를 통째로 미러링하는 패키지를 만들지 않는다. Room의
비즈니스 규칙(강퇴, 방장 위임 등)이 REST 처리 코드와 WS 처리 코드로 쪼개져 서로 다른 패키지에
놓이면, 상태 변경과 그 전파가 어긋나기 쉽다(Redis는 갱신했는데 브로드캐스트를 빠뜨리는 등).

## 상태 변경 + 전파 흐름 원칙

프로젝트 공통 정책(상태 변경 요청은 API, 변경 전파는 WebSocket)에 따라, **하나의 유스케이스 안에서
상태 변경과 그 전파가 같은 곳(service)에서 함께 트리거된다.**

- Controller는 WS 발행 여부를 몰라도 된다. Service만 호출하고 끝.
- Service가 상태를 변경한 직후, 같은 메서드 안에서 자신의 도메인 `ws/` 퍼블리셔를 호출해
  전파까지 완결시킨다.
- Controller가 `RoomEventPublisher`나 `StompBroadcaster`를 직접 호출하지 않는다 — "무슨 이벤트를
  보낼지"는 비즈니스 판단이라 서비스 계층의 책임이다.

### 예시 흐름 (강퇴)

```
RoomController.kick(roomCode, targetToken)
  → RoomService.kick(roomCode, targetToken)
       1. Redis: participant 제거, banned SET에 추가
       2. roomEventPublisher.notifyKicked(roomCode, targetToken)   // 같은 트랜잭션/메서드 안에서
  → 컨트롤러는 HTTP 응답만 반환
```

## 체크리스트 (PR 리뷰 시)

- [ ] 새 WS 이벤트가 관련 도메인의 `ws/` 서브패키지 안에 있는가 (domain/ws/* 아님)
- [ ] Controller가 WS 퍼블리셔를 직접 호출하고 있지 않은가 (Service를 거쳤는가)
- [ ] `global/`에 도메인 로직(이벤트 이름, payload 구조 등)이 새어 들어가지 않았는가
- [ ] 새 도메인을 추가할 때 `controller/service/ws/repository/dto` 표준 하위 구조를 따랐는가
