# 참가자 퇴장 처리 흐름

방을 떠난 사람이 **방 상태**와 **진행 중인 게임**에 각각 어떻게 반영되는지 정리한다.
두 계층이 분리돼 있고, 예전에 게임 계층이 비어 있어서 생긴 버그(닌자 판이 안 끝남)를
고치면서 정리한 구조다.

## 1. 퇴장이 감지되는 두 경로

| 경로 | 언제 | 걸리는 시간 | reason |
| --- | --- | --- | --- |
| REST `DELETE /api/rooms/{roomId}/members/me` | 나가기 버튼, 창/탭 닫기(`pagehide`) | 즉시 | `LEFT` |
| 하트비트 TTL 만료 | 위 요청이 못 닿은 경우(브라우저 강제 종료, 네트워크 단절, 절전) | 최대 15초 | `TIMEOUT` |
| 방장의 강퇴 `DELETE .../members/{participantId}` | 방장이 내보냄 | 즉시 | `KICKED` |

창/탭 닫기는 프론트가 `pagehide`에서 `keepalive: true` fetch로 같은 DELETE를 보낸다
(`roomApi.leaveRoomOnUnload`). 일반 fetch는 문서가 언로드되면 취소되고, `sendBeacon`은
Authorization 헤더와 DELETE를 못 써서 이 방식을 쓴다. 못 닿아도 하트비트 만료가 뒷정리를
하므로 최악의 경우 15초 뒤에 같은 결과가 된다.

leave Lua 스크립트에는 방 상태 가드가 없다 — **게임 진행 중에도 퇴장은 그대로 처리된다.**

## 2. 방 계층 (room 도메인)

세 경로 모두 아래를 똑같이 수행한다. 갈라지면 한쪽에서만 방장 위임이나 브로드캐스트가
빠지므로, 하트비트 경로는 `RoomConnectionService.removeIfExpired` 한 곳에 모아 뒀다.

1. Redis에서 참가자 제거 (`participants` SET, `participant:{id}` HASH, 닉네임 SET)
2. 방장이었으면 입장 순서상 다음 참가자에게 연쇄 위임
3. STOMP `member:left` 브로드캐스트 (+ 위임됐으면 `host:changed`도 별도로)
4. 마지막 한 명이 나갔으면 방 키 전체 삭제
5. **`ParticipantLeftEvent` 발행** — 게임 계층으로 넘어가는 유일한 다리

프론트는 `member:left`를 받아 참가자 목록에서 지운다. 캠 타일은 LiveKit 연결이 끊기면서도
사라지므로, 이 이벤트를 놓쳐도 화면에서는 없어진다(이름·방장 자격 같은 방 상태는 STOMP가 원본).

## 3. 게임 계층 (game 도메인)

### 구조

```
ParticipantLeftEvent
        ↓
GameParticipantEventListener          ← game/common/service, 단일 창구
  · 남아 있는 CONNECTED 인원을 한 번만 센다
  · 등록된 모든 핸들러에게 알린다 (한 핸들러가 터져도 나머지는 계속)
        ↓
GameParticipantLeaveHandler           ← 게임마다 하나씩 구현
  ├ NinjaParticipantLeaveHandler
  ├ CharadesParticipantLeaveHandler
  └ FetchObjectParticipantLeaveHandler
```

`GameSessionStarter`("이 게임의 세션을 어떻게 여는가")와 같은 축이다. 게임을 추가할 때
이 인터페이스만 구현하면 퇴장 처리 쪽은 손대지 않아도 된다.

### 왜 게임 이름으로 디스패치하지 않는가

"지금 무슨 게임이 진행 중인지"는 코스만 아는 정보인데, `game` → `course` 의존은 순환이다
(코스가 게임을 열기 때문. `GameSessionFinishedEvent` 주석 참고). 그래서 리스너는 어느
게임인지 모른 채 **전부에게 알리고**, 각 구현체가 자기 Redis 세션 상태의 유무로 스스로
판단한다.

> **구현체는 자기 세션이 열려 있지 않으면 반드시 no-op 해야 한다.** 새 게임을 추가할 때
> 이 가드를 빠뜨리면 다른 게임이 진행 중일 때 엉뚱하게 끼어든다.

`connectedCount`(퇴장 반영 후 남아서 연결돼 있는 인원)만 리스너가 계산해 넘긴다. "이제
게임을 이어갈 수 없다"의 기준은 게임마다 달라서 판단 자체는 구현체 몫이다.

### 게임별 처리

**닌자** — 생존자 집합이 방 참가자 집합과 별개 Redis 키라, 손대지 않으면 떠난 사람이
계속 생존자로 남는다.

1. 세션 참가자 집합(`...:participants`)에서 제거
   → 안 하면 판마다 전원을 되살리는 `startRound`가 다음 판에 유령을 풀피로 부활시킨다.
   남은 인원이 `MIN_PLAYERS`(2) 미만이면 `startRound`가 스스로 게임을 끝낸다.
2. 이번 판 생존자 집합에서 탈락 처리
3. 남은 생존자가 1명 이하 → 판 종료 (점수 저장 → 마지막 판이면 게임 종료)
4. 공격권을 쥔 채 나갔으면 생존자가 2명 이상이어도 그 교환을 닫는다
   → 안 하면 대상 지정 창(15초)이 통째로 비었다가 `handleTargetTimeout`이 "떠난 사람의
   공격"으로 남은 사람을 때린다.

교환이 공격 없이 닫힌 뒤의 전개(`advanceAfterExchangeClosed`)는 전원 콤보 실패
(`handleTimeout`)와 공유한다. 이벤트도 둘 다 `ninja:round-timeout`이다 — 이 payload는
"이 교환이 이렇게 닫혔고 결과 스냅샷은 이거다"라는 상태 동기화용이고 프론트도 그렇게만
쓰므로(문구를 띄우지 않는다) 이탈 전용 이벤트를 따로 두지 않았다.

**몸으로 말해요** — 연결 인원이 1명 이하면 세션 종료. 아니면 떠난 사람이 지금 표현자일
때만 그 턴을 무효(`INVALIDATED`) 처리하고 다음 표현자로 넘긴다.

**물건 가져오기** — 라운드가 시간 기반이라 판이 멈추지는 않지만, 제출 집계 Lua가 참가자
집합의 크기로 "전원 제출"을 판정한다. 떠난 사람을 빼지 않으면 남은 사람이 다 제출해도
라운드가 조기에 닫히지 않고 매 라운드 제한시간을 다 태운다. 집합에서 빼고, 연결 인원이
1명 이하면 세션을 끝낸다.

## 4. 고쳤던 버그 (회귀 주의)

`ParticipantLeftEvent` 리스너가 몸으로 말해요에만 있었다. 닌자는 아무도 안 들어서 떠난
사람이 생존자로 남았고, 판 종료 조건 `aliveCount <= 1`이 유령까지 세는 바람에 판이 끝나지
않았다. 완전히 멈추지는 않았다 — 교환 타임아웃 HP 감쇠(20)와 교환 상한(50)이 결국 판을
끝냈다. 다만 그 감쇠가 **생존자 전원 대상**이라, 유령이 죽길 기다리는 동안 살아남은 진짜
플레이어의 HP도 같이 깎였다. 체감상 "게임이 안 끝난다".

관련 테스트: `NinjaGameServiceTest`(이탈 4개), `GameParticipantEventListenerTest`(2개),
`CharadesGameServiceTest`(이탈 3개).

## 5. 재입장

강제 퇴장된 사람은 게임 진행 중이 아니면 다음 게임 시작 전까지 재입장할 수 있다. 게임
진행 중에 나갔으면 그 게임이 끝날 때까지 재입장할 수 없다. 강퇴(`KICKED`)당한 사람은
그 방에 다시 들어올 수 없다.
