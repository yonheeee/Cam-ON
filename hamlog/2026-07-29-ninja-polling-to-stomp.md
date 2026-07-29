# 닌자 실시간 상태: 폴링 → STOMP 이벤트 전환 (S15P11B110-111)

> 2026-07-29 · 양해미 · 브랜치 `feature/ninja-realtime-stomp` (선행: `refactor/ninja-bout-to-round`)

## 왜 했나

닌자 게임 화면은 지금까지 **GET `/api/games/{gameId}/ninja/state`를 1.5초마다 폴링**해서
그렸다. 닌자 프론트가 만들어질 당시 백엔드에 ninja WS 이벤트가 없어서 임시로 폴링을 쓰고
"나중에 STOMP로 전환"이라는 주석만 남겨둔 상태였다. 이후 백엔드가 이벤트 5종을 발행하기
시작했지만, 프론트는 그 이벤트를 "즉시 재폴링 트리거"로만 쓰는 절충(하이브리드)에 머물러 있었다.

프로젝트 원칙이 **"상태 변경 요청은 REST, 변경 전파는 WebSocket"** 이므로, 상태 전파를
이벤트 기반으로 완전히 전환했다. 부수 효과로 REST 트래픽(참가자 4명 기준 초당 ~2.7회 → 0회)과
전파 지연(최대 1.5초 → 즉시)이 사라진다.

## 구조: 전 / 후

```
[전] 1.5초마다 GET /state (전체 스냅샷)  +  ninja:* 이벤트 = "지금 다시 읽어" 신호
     상태의 단일 소스 = GET /state

[후] 입장/STOMP (재)연결 시 GET /state 1회 (스냅샷 동기화)
     이후에는 ninja:* 이벤트 payload로만 증분 갱신 (재조회 없음, setInterval 없음)
```

핵심 규칙: **이벤트는 "발생 순간 접속해 있던 사람"에게만 간다.** 그래서 새로고침(F5)이나
끊김 복귀로 이벤트 공백이 생긴 클라이언트는, STOMP가 다시 연결되는 순간(`onConnect`)
GET `/state` 한 번으로 따라잡고 그 뒤부터 다시 이벤트만 소비한다. 구독을 걸어둔 **뒤에**
스냅샷을 받아야 스냅샷과 다음 이벤트 사이에 빈틈이 없다 (`useNinjaRealtime.ts` 참고).
이 마지막 GET 1회는 폴링이 아니라 재접속 동기화다 — 이것마저 없애면 새로고침한 사람이
빈 화면이 된다.

## 이벤트 계약 (프론트 리듀서가 소비하는 것)

| 이벤트 | 언제 | payload 핵심 | 이번에 추가된 필드 |
| --- | --- | --- | --- |
| `ninja:round-started` | 교환(콤보 하나) 시작 | round, exchange, deadlineAt | **alivePlayers, hp** — 판 시작(전원 부활/HP 리셋)을 이벤트만으로 반영하기 위해 |
| `ninja:attack-won` | 공격권 선점 | attackerToken, skillId | — |
| `ninja:attack-resolved` | 대상 지정→데미지 적용 | targetHpAfter, targetEliminated, phase, effectUntil, nextRoundAt, roundEnded, ending | **roundResult, sessionTotals** — roundEnded=true일 때만. 판 결과창 재료 |
| `ninja:round-timeout` | 30초 내 아무도 콤보 미완성 | phase, nextRoundAt | **roundResult, sessionTotals** — 이 타임아웃이 판을 끝냈을 때(교환 상한)만 |
| `ninja:game-ended` | 마지막 판 종료 | ranking | **sessionTotals** — 종료 화면의 "n위 · m점"에 둘 다 필요 |

- 요구 스킬(콤보 내용)은 여전히 REST GET(`/rounds/{round}/skill`)이다 — "라운드 콘텐츠는
  push가 아니라 프론트가 필요할 때 GET"이라는 API 명세 원칙이라 전환 대상이 아니다.
- HP는 `attack-resolved`의 targetHpAfter로 증분 갱신하고, 판이 바뀔 때 `round-started`의
  hp 스냅샷으로 리셋된다. 중간 계산이 어긋날 방법이 없다.

## 바뀐 파일

**백엔드** — payload 보강 (기존 필드는 그대로, 추가만)
- `ws/payload/RoundStartedPayload.java` — alivePlayers, hp 추가
- `ws/payload/AttackResolvedPayload.java` — roundResult, sessionTotals 추가 (roundEnded일 때만 채움)
- `ws/payload/RoundTimeoutPayload.java` — 〃
- `ws/payload/GameEndedPayload.java` — sessionTotals 추가
- `service/NinjaGameService.java` — 발행부 4곳에서 위 필드 채움, hpSnapshot() 헬퍼 추출

**프론트**
- `hooks/useNinjaRound.ts` — `setInterval` 폴링 제거. `poll` → `syncState`(마운트/재연결 시만),
  이벤트별 리듀서(`handleNinjaEvent`) 추가. attack/target 제출 후 재조회도 제거(이벤트가 반영).
  교환 타이머가 서버 `deadlineAt` 기준으로 바뀜(전원 동일값, 스냅샷 진입 시에만 30초 근사 폴백).
- `hooks/useNinjaRealtime.ts` — 이벤트 이름만 넘기던 콜백을 (이름, payload)로 확장,
  (재)연결 시 `onConnected` 콜백 추가.
- `api/ninjaApi.ts` — 이벤트 payload 타입 5종 추가.

## 검증

- 백엔드 테스트 전체 통과, 프론트 tsc/vite/oxlint 통과.
- 실스택 E2E(STOMP 구독 + REST로 2인 닌자 3판 자동 플레이, 19개 체크 전부 통과):
  - 교환 시작마다 alivePlayers/hp/deadlineAt 수신, 새 판 시작 시 HP 전원 100 리셋 확인
  - 판 진행 중 attack-resolved의 roundResult=null, 판 종료 순간엔 roundResult(순위 5/4점)와
    sessionTotals(누적) 동봉 확인 — 3판 모두
  - 첫 교환을 일부러 30초 태워 round-timeout(INTERMISSION + nextRoundAt) 경로 확인
  - game-ended에 ranking+sessionTotals 동봉, 종료 후 GET /state 스냅샷으로 새로고침 복구
    재료(ranking/sessionTotals)가 완전한 것 확인

## 주의/남은 것

- **BE와 FE를 함께 배포해야 한다.** payload는 필드 추가라 하위호환이지만, 프론트 전환
  (폴링 제거)은 새 필드가 있는 백엔드를 전제한다. 옛 백엔드 + 새 프론트 조합이면 판 결과창
  점수가 안 뜬다.
- 게임 도중 손 인식으로 하는 실제 브라우저 2인 플레이(F5 복구 눈 확인 포함)는 자동화가
  안 되는 부분이라, 머지 전에 한 번 해보는 것을 권장: 게임 중간(HP 깎이고 누적 점수 있는
  상태)에 한 명이 F5 → 라운드/HP/누적 점수/phase가 그대로 복원되고 이후 이벤트도 정상
  수신되는지.
- 참가자 이탈은 닌자 상태를 바꾸지 않으므로(리스너 없음) 이벤트 공백 구멍이 아니다.
  나중에 "이탈자 자동 탈락" 같은 규칙이 생기면 그때 이벤트도 함께 추가해야 한다.
