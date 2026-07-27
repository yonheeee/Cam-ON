# 대기방(Lobby) 디자인 스펙 — Pixel Arcade Plaza 테마

> 소스: `src/theme.css`(공통 토큰), `src/features/room/components/LobbyScreen.css`,
> `src/features/room/components/RoomLobby.css`, `src/features/chat/components/ChatPanel.css`
> (`--docked` 변형), `src/index.css`(전역 베이스). `develop-frontend` 브랜치 기준으로 실제
> 코드에서 역추출한 값이며, Figma 원본 수치와 다를 수 있다(테마 토큰 주석에 "초안 수치 —
> Figma 적용 시 재검토"라고 명시돼 있음).

## 1. 색상 팔레트

### 1.1 코어 색상 (`theme.css` `:root`)

| 변수 | HEX | 용도 |
| --- | --- | --- |
| `--pap-sky-blue` | `#80d8f4` | 랜딩/진입 화면 배경(하늘) |
| `--pap-plaza-cream` | `#fff0c8` | 대기방 전체 배경 |
| `--pap-arcade-teal` | `#2fc7be` | 포인트 컬러(버튼 teal 변형, 1P 컬러, 포커스 아웃라인) |
| `--pap-festival-coral` | `#f06478` | 경고/에러, 코럴 버튼 변형, 2P 컬러 |
| `--pap-play-yellow` | `#ffd966` | 기본(primary) 버튼, 코드 티켓 짝수 타일, 세트 칩, 3P 컬러 |
| `--pap-lavender` | `#8c78d8` | 방장 배지, 4P 컬러 |
| `--pap-ink` | `#2b1833` | 텍스트·테두리 기본색(거의 모든 border/글자) |
| `--pap-white` | `#fffdf5` | 카드/입력/타일 배경 (완전한 흰색이 아닌 아이보리 톤) |
| `--pap-success-lime` | `#b8ff4f` | READY 배지, 준비완료 버튼 |

### 1.2 플레이어 대표색 (1~4P)

| 슬롯 | 변수 | 실제 색 |
| --- | --- | --- |
| 1P | `--pap-player-1` | `--pap-arcade-teal` (`#2fc7be`) |
| 2P | `--pap-player-2` | `--pap-festival-coral` (`#f06478`) |
| 3P | `--pap-player-3` | `--pap-play-yellow` (`#ffd966`) |
| 4P | `--pap-player-4` | `--pap-lavender` (`#8c78d8`) |

참가자 타일 테두리(`.lobby-tile--p1~4`), 사이드 참가자 목록 칩(`.room-lobby__player-chip--1~4`)에
동일하게 쓰인다.

### 1.3 파생/반투명 색상 (변수 없이 직접 값 사용)

| 값 | 용도 |
| --- | --- |
| `rgb(43 24 51 / 0.45)` | 모달 백드롭 (ink 45% — hex `#2b1833`와 동일 RGB) |
| `rgb(43 24 51 / 0.35)` | 픽셀 카드 그림자 |
| `rgb(43 24 51 / 0.25)` | 참가자 타일 그림자, 점선 구분선, 빈 슬롯 아이콘 |
| `rgb(43 24 51 / 0.2)` | 채팅 패널 점선 구분선 |
| `rgb(43 24 51 / 0.3)` | 빈 참가자 슬롯 테두리(dashed) |
| `rgb(43 24 51 / 0.4)` | 상태 아이콘, 빈 슬롯 힌트 텍스트 |
| `#1b1230` | 참가자 타일 배경(비디오 로딩 전) |
| `#4a9d23` | "준비 완료" 텍스트 (라임이 흰 배경에서 안 읽혀서 더 어둡게 조정) |
| `#1d8b85` | 채팅 닉네임(도킹 모드) — arcade-teal을 흰 배경에서 읽히도록 어둡게 조정 |
| `#d94f63` | 캠/마이크 OFF 버튼 hover (코럴보다 어둡게) |

**패턴**: 반투명 오버레이는 항상 `--pap-ink`(`#2b1833` = `rgb(43 24 51)`)를 베이스로 알파값만
바꿔 쓴다. 원색을 밝은 배경에 그대로 쓰면 대비가 부족한 경우(라임, 틸) 별도로 한 톤 어둡게
하드코딩한 값을 쓴다.

## 2. 타이포그래피

### 2.1 폰트 패밀리

| 변수 | 스택 | 용도 |
| --- | --- | --- |
| `--pap-font-pixel` | `'Galmuri11', 'Galmuri9', monospace` | 로고, 코드 티켓, 배지(READY/방장), 토스트, 버튼, 인풋, 카드 타이틀 — "숫자·강조 텍스트" 전반 |
| `--pap-font-body` | `'Pretendard', system-ui, 'Segoe UI', 'Malgun Gothic', sans-serif` | 대기방 본문 전체(`.lobby-screen`, `.room-lobby`), 채팅 |

Galmuri11은 한글 지원 픽셀 폰트(SIL OFL), CDN(`cdn.jsdelivr.net/npm/galmuri`)에서 로드.
원칙: **본문은 가독성 위주 고딕(Pretendard), 로고·큰 숫자·강조 요소만 픽셀 폰트.**

전역 베이스(`index.css`, 랜딩 등 비-로비 화면에 영향): `--sans`/`--heading` =
`system-ui, 'Segoe UI', Roboto, sans-serif`, 기본 `font: 18px/145%`, `letter-spacing: 0.18px`.
로비 화면은 `font-family: var(--pap-font-body)`로 이 전역값을 오버라이드한다.

### 2.2 크기 표 (실사용 값, px 기준)

| 요소 | font-size | font-weight | 비고 |
| --- | --- | --- | --- |
| 참여 코드 타일 한 글자 (`.lobby-screen__code-tile`) | 18px | - | 28×32px 타일 |
| 참가자 타일 이름 (`.lobby-tile__name`) | 15px | 800 | ellipsis 처리 |
| 참가자 타일 역할 (`.lobby-tile__role`) | 12px | - | opacity 0.55 |
| READY 배지 (`.lobby-tile__ready-badge`) | 12px | - | 픽셀 폰트, `rotate(3deg)` |
| 사이드바 패널 제목 (`.lobby-screen__panel-title`) | 15px | - | |
| 사이드바 패널 부제 (`.lobby-screen__panel-sub`) | 12px | - | opacity 0.6 |
| 게임 구성 세트 칩 라운드 수 (`.lobby-screen__set-rounds`) | 12px | - | |
| 세트 칩 순서 뱃지 (`::before`) | 10px | - | 픽셀 폰트, 16×16px 원형 |
| 하단 액션 버튼 (`.lobby-screen__actions .pap-pixel-btn`) | 15px | - | |
| 작은 픽셀 버튼 (`.lobby-btn-sm`, `.room-lobby__btn-sm`) | 12~13px | - | |
| 에러 메시지 (`.lobby-screen__error`, `.room-lobby__error`) | 13px | 600 | 코럴색 |
| 공용 픽셀 버튼/인풋 (`.pap-pixel-btn`, `.pap-input`) | 20px | - | 기본 크기(사이드바 등에서 sm 변형으로 축소해 사용) |
| 토스트 (`.pap-toast`) | 15px | - | 픽셀 폰트 |
| `room-lobby` 코드 표시 (`.room-lobby__code`) | 22px | - | `letter-spacing: 3px` |
| `room-lobby` 코드 라벨 (`.room-lobby__code-label`) | 12px | - | opacity 0.7 |
| 방장 배지 (`.room-lobby__host-badge`) | 11px | - | 픽셀 폰트 |
| 채팅 패널 전체 (`.chat-panel`) | 0.85rem (≈13.6px) | - | rem 단위 유일 사용처 |
| 채팅 닉네임 (`.chat-panel__nickname`) | 상속 | 600 | |
| 참가자 타일 빈 슬롯 아이콘 (`.lobby-tile__empty-slot`) | 26px | - | |
| 참가자 타일 빈 슬롯 힌트 (`.lobby-tile__empty-hint`) | 13px | - | body 폰트 |

## 3. 여백 / 간격

### 3.1 레이아웃 큰 단위

| 위치 | 값 |
| --- | --- |
| `.lobby-screen` 전체 패딩 | `16px 20px` |
| `.lobby-screen` 내부 요소 간 gap(header/body 사이) | `12px` |
| `.lobby-screen__header` 요소 간 gap | `20px` |
| `.lobby-screen__body` (스테이지-사이드바) gap | `16px` |
| `.lobby-screen__grid` (참가자 타일 그리드) gap | `16px` |
| `.lobby-screen__sidebar` 너비 / 내부 gap | `320px` / `12px` |
| `.room-lobby` 오버레이 패딩 | `16px` (위치: `left/top: 1rem`, 너비 `320px`) |

### 3.2 컴포넌트 내부 패딩

| 요소 | padding |
| --- | --- |
| `.lobby-screen__course` (게임 구성 카드) | `14px` |
| `.lobby-screen__chat` | `14px 14px 8px` |
| `.lobby-tile__bar` (참가자 이름바) | `8px 12px` |
| `.pap-pixel-btn` (기본) | `14px 28px` |
| `.lobby-screen__actions .pap-pixel-btn` | `12px 8px` |
| `.lobby-btn-sm` / `.room-lobby__btn-sm` | `7px 10px` / `8px 12px` |
| `.pap-input` | `12px 14px` |
| `.lobby-screen__set` (세트 칩) | `7px 10px` |
| `.pap-toast` | `10px 20px` |
| `.chat-panel__messages` | `0.5rem` |
| `.chat-panel__form` | `0.5rem` |

### 3.3 작은 간격(gap) 모음

`4px`(코드 타일 사이·타일 뱃지 여백), `6px`(아이콘 버튼 그룹·패널 액션), `8px`(대부분의
아이콘+텍스트 조합, 세트 칩 리스트 gap, 참가자 리스트 아이템 gap), `10px`(액션 버튼 사이) 순으로
8px 기준 그리드에 4px 보정값을 섞어 쓰는 패턴.

## 4. 보더 / 그림자 / 라운드 (픽셀 아트 룩)

| 토큰/값 | 내용 |
| --- | --- |
| `--pap-border-width: 3px` | 기본 픽셀 테두리 두께(버튼/인풋/카드) |
| `--pap-shadow-offset: 4px` | 기본 "하드 섀도" 오프셋(블러 없이 `4px 4px 0 0`) — hover 시 `+1px`, active 시 오프셋만큼 이동하며 그림자 소멸(눌리는 느낌) |
| `--pap-radius: 6px` | 기본 라운드(과한 라운드 지양, 계단 형태 방지 목적) |
| 참가자 타일 (`.lobby-tile`) | `border: 4px solid`(플레이어 컬러로 오버라이드), `border-radius: 10px`, `box-shadow: 5px 5px 0 0 rgb(43 24 51 / .25)` |
| 코드 티켓 타일 | `border: 2px solid var(--pap-ink)`, `border-radius: 5px`, `box-shadow: 2px 2px 0 0`, 홀/짝 `rotate(±2deg)`로 티켓 느낌 |
| 아이콘 버튼(원형류) | `border: 2px solid`, `border-radius: 6px` |
| 점선 구분선 | `2px dashed rgb(43 24 51 / 0.2~0.25)` — 채팅/사이드바 섹션 구분에 반복 사용 |
| 빈 참가자 슬롯 | `border: 3px dashed rgb(43 24 51 / 0.3)`, 그림자 없음 |

**패턴 요약**: 그림자는 항상 블러 0, 방향 우하단(`+x +y`) 고정 오프셋 — "8bit 하드 섀도" 스타일.
버튼 상호작용은 transform(이동)으로 그림자가 줄었다 늘었다 하며 눌림/떠오름을 표현한다
(`transition: transform 60ms ease-out, box-shadow 60ms ease-out`).

## 5. 상호작용 상태

- **hover**: `translate(-1px, -1px)` + 그림자 오프셋 +1px (뜨는 느낌)
- **active**: `translate(shadow-offset, shadow-offset)` + 그림자 `0 0 0 0` (완전히 눌림)
- **disabled**: `opacity: 0.55`, `cursor: not-allowed`
- **준비 완료 고정 상태** (`.lobby-screen__ready-btn--on`): active 상태를 그대로 유지("철컥" 고정), hover에서도 원복 안 됨
- **포커스**: `outline: 3px solid var(--pap-arcade-teal); outline-offset: 2px` (인풋)
- 모달/토스트 등장 애니메이션: `120ms ease-out`, `translateY(8px→0)` + opacity, `prefers-reduced-motion: reduce`에서 전부 비활성화

## 6. 참고

- 색상·크기 상당수가 "초안 수치, Figma 적용 시 재검토" 상태(`theme.css` 상단 주석)이므로, 실제
  Figma 시안이 확정되면 이 문서도 함께 갱신 필요.
- `index.css`의 전역 타이포(`--sans`, 18px/145%, dark mode 팔레트 등)는 랜딩/기본 페이지용이며,
  대기방(`.lobby-screen`, `.room-lobby`)은 `--pap-font-body`로 오버라이드해 별도 스코프로
  동작한다 — 다크모드 대응은 대기방 쪽에 별도로 없음(라이트 전용, cream 배경 고정).
