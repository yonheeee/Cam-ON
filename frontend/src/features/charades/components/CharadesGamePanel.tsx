import { ParticipantTile, useLocalParticipant, useParticipants, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { roomApi } from '../../room/api/roomApi';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import { SpeakingIndicator } from '../../webrtc/components/SpeakingIndicator';
import { useSpeakingIdentities } from '../../webrtc/hooks/useSpeakingIdentities';
import { useCountdownSound } from '../../sound/hooks/useCountdownSound';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useCharadesRound } from '../hooks/useCharadesRound';
import { RESULT_BANNER_HOLD_MS } from '../lib/resultBannerHold';
import './CharadesGamePanel.css';

interface CharadesGamePanelProps {
  roomId: string;
  gameId: number;
  accessToken: string;
  onActiveChange: (active: boolean) => void;
  /** 로고 클릭 → 확인 팝업 → 방 나가기 (확정안: 방 안에서 로고는 항상 확인 팝업 경유) */
  onLeave: () => void;
}

type TrackRef = ReturnType<typeof useTracks>[number];

// 정답 입력 길이 제한 — 말풍선이 한 줄을 넘기면 사이드바 세로 여백(--bubble-space)을 넘겨 스크롤바가 생긴다.
const GUESS_MAX_LENGTH = 20;

// .charades-screen의 좌우 padding — 사이드바 여백 계산 기준. CSS 값을 바꾸면 여기도 같이 바꿀 것.
const SCREEN_PADDING_PX = 28;

// 몸으로 말해요 화면 — 메인 스테이지는 항상 "지금 설명 중인 사람"(표현자, 나여도 마찬가지)이 크게,
// 나머지 참가자는 사이드바에 나열한다. 제시어/정답 입력은 상단 카드 하나에 role에 따라 다른 내용을 채운다.
export function CharadesGamePanel({
  roomId,
  gameId,
  accessToken,
  onActiveChange,
  onLeave,
}: CharadesGamePanelProps) {
  const [confirmLeave, setConfirmLeave] = useState(false);
  const { localParticipant } = useLocalParticipant();
  const participants = useParticipants();
  const myParticipantId = localParticipant.identity || null;

  const {
    turn,
    totalTurnsInRound,
    presenterId,
    isPresenter,
    phase,
    myWord,
    revealedWord,
    chatLog,
    lastAnswererId,
    lastInvalidReason,
    expiresAt,
    timeLeftSeconds,
    gameEnded,
    gameEndPending,
    error,
    submitGuess,
  } = useCharadesRound(roomId, gameId, accessToken, myParticipantId);

  // 참가자별 "가장 최근에 제출한 정답" — 사이드바 캠 아래 말풍선용. chatLog는 턴마다 비워지므로 턴별로 리셋된다.
  const latestGuessByParticipant = useMemo(() => {
    const map: Record<string, string> = {};
    for (const entry of chatLog) map[entry.participantId] = entry.text;
    return map;
  }, [chatLog]);

  const nicknameByToken = useMemo(() => {
    const map: Record<string, string> = {};
    for (const p of participants) {
      if (p.name) map[p.identity] = p.name;
    }
    if (localParticipant.name) map[localParticipant.identity] = localParticipant.name;
    return map;
  }, [participants, localParticipant.identity, localParticipant.name]);

  // 플레이어 색은 "방 입장 순서" 고정 — DOM 순서(nth-child)로 칠하면 표현자가 빠진 자리에 따라
  // 클라이언트마다 같은 사람이 다른 색으로 보인다. 대기방(LobbyScreen)과 같은 소스를 써서 색도 이어진다.
  const [joinOrder, setJoinOrder] = useState<string[]>([]);
  useEffect(() => {
    let cancelled = false;
    roomApi
      .getRoom(roomId, accessToken)
      .then((snapshot) => {
        if (!cancelled) setJoinOrder(snapshot.participants.map((p) => p.participantId));
      })
      .catch((err) => {
        // 실패해도 fallback(player-1)로 게임은 계속되지만, 원인 없이 삼키면 디버그가 안 되니 로그는 남긴다
        console.error('[charades] 입장 순서 조회 실패 — 참가자 색이 전부 기본값으로 표시됩니다', err);
      });
    return () => {
      cancelled = true;
    };
  }, [roomId, accessToken]);

  const seatColor = (token: string) => {
    const index = joinOrder.indexOf(token);
    return `var(--pap-player-${index < 0 ? 1 : (index % 4) + 1})`;
  };

  const displayName = (token: string | null) => {
    if (!token) return '???';
    if (token === myParticipantId) return '나';
    return nicknameByToken[token] ?? '참가자';
  };

  const active = phase !== null;
  useEffect(() => {
    onActiveChange(active);
  }, [active, onActiveChange]);

  // 캠 트랙 유무로 참가자 존재를 판단하면 카메라 트랙이 아직 없는 참가자가 통째로 사이드바에서
  // 빠진다 — 실제 참가자 명단(LiveKit participants)을 기준으로 삼고 트랙은 있으면만 붙여준다.
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });
  const trackByIdentity = useMemo(
    () => new Map(tracks.map((t) => [t.participant.identity, t] as const)),
    [tracks],
  );
  const rosterIds = useMemo(() => {
    const ids = new Set(participants.map((p) => p.identity));
    if (myParticipantId) ids.add(myParticipantId);
    return Array.from(ids);
  }, [participants, myParticipantId]);
  const speakingIds = useSpeakingIdentities();
  const presenterTrack = presenterId ? trackByIdentity.get(presenterId) : undefined;
  const otherIds = rosterIds.filter((id) => id !== presenterId);

  const [guessText, setGuessText] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  // 턴이 열리면 입력창에 바로 포커스를 준다 — 매 턴 입력칸을 클릭할 필요가 없게.
  useEffect(() => {
    if (phase !== 'playing' || isPresenter || confirmLeave) return;
    inputRef.current?.focus();
  }, [phase, isPresenter, confirmLeave]);

  // 포커스를 잃은 뒤에도(제출 버튼 클릭 등) 글자를 치기 시작하면 입력창이 받아가게 한다.
  // keydown 시점에 focus()를 옮기면 그 글자부터 입력창에 들어간다.
  useEffect(() => {
    if (phase !== 'playing' || isPresenter || confirmLeave) return;
    const onKeyDown = (e: KeyboardEvent) => {
      // 단축키(⌘/Ctrl/Alt)와 기능키는 건드리지 않는다 — 문자 한 글자짜리 키만 가로챈다.
      if (e.ctrlKey || e.metaKey || e.altKey || e.key.length !== 1) return;
      const input = inputRef.current;
      const active = document.activeElement;
      if (!input || active === input) return;
      // 다른 입력 요소(채팅 등)에 이미 타이핑 중이면 뺏지 않는다.
      if (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement) return;
      input.focus();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [phase, isPresenter, confirmLeave]);

  // 사이드 캠 우측 여백 = 메인 캠 좌측 여백. 메인 캠은 16:9라 창 크기에 따라 좌우로 남는 여백이
  // 달라져서 CSS만으로는 그 값을 알 수 없다 — 실측해 --edge-shift(여백 - 화면 padding)로 넘긴다.
  const stageBoxRef = useRef<HTMLDivElement>(null);
  const [edgeShift, setEdgeShift] = useState(0);
  useLayoutEffect(() => {
    const box = stageBoxRef.current;
    if (!box) return;
    const measure = () =>
      setEdgeShift(Math.max(0, box.getBoundingClientRect().left - SCREEN_PADDING_PX));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    observer.observe(document.documentElement);
    return () => observer.disconnect();
  }, [active, gameEnded]);

  if (!active || gameEnded) return null;

  const submitDisabled = phase !== 'playing';
  // 제시어(표현자)와 입력창(맞추는 사람)은 같은 자리의 직사각형 카드 하나로 합친다. 턴이 진행되는
  // 동안(playing~결과)엔 항상 이 카드가 뜨고, 그 밑에 제한시간 바가 붙는다.
  //
  // preview 구간에도 카드를 "숨기되 자리는 남긴다"(--ghost). 언마운트하면 좌측 컬럼 높이가 줄고
  // → 16:9 메인 캠이 커지고 → --edge-shift 재측정으로 사이드바까지 좌우로 튄다.
  const promptGhost = phase === 'preview';

  return (
    // .camon-stage = 뷰포트를 덮는 전체 화면 껍데기(스크롤 없음). 픽셀 폰트 상속과
    // letter-spacing: 0 리셋(Mona12는 자간 0이 원본)이 여기서 온다 — 대기방과 같은 방식.
    <div className="charades-screen camon-stage">
      <header className="charades-topbar">
        {/* 확정안: 방 안에서 로고 클릭 = 바로 이동이 아니라 나가기 확인 팝업 */}
        <img
          className="charades-topbar__logo"
          src="/assets/cam-on-logo.png"
          alt="CAM, ON!"
          onClick={() => setConfirmLeave(true)}
        />
        <BackgroundMusic
          source="/assets/sounds/silent-charades.mp3"
          className="charades-topbar__music-toggle"
        />
        {/* 단일 라운드 정책(참가자 전원이 한 번씩 표현하면 게임 종료)이라 라운드가 아니라
            "몇 번째 표현자인지"가 진행도다 — 서버가 turn/totalTurnsInRound로 내려준다. */}
        <div className="charades-header__badge">
          <span className="charades-header__round">
            TURN {turn} / {totalTurnsInRound}
          </span>
        </div>
      </header>

      {phase === 'preview' && (
        <div className="charades-preview-overlay">
          <p className="charades-preview-overlay__title">
            {isPresenter ? '다음 표현자는 나예요!' : `다음 표현자: ${displayName(presenterId)}`}
          </p>
        </div>
      )}

      {/* 정답/시간 초과 모두 같은 팝업으로 이번 턴 제시어를 공개한다.
          제시어는 턴이 끝날 때 서버가 answer-revealed·round-timeout에 실어주는 revealedWord가
          원본이라 표현자든 아니든 같은 값을 본다. myWord(표현자 전용)는 그 이벤트를 놓쳤을 때의
          폴백일 뿐이다. */}
      {(phase === 'correct' || phase === 'timeout') && (
        <CharadesResultBanner
          tone={phase}
          word={revealedWord ?? (isPresenter ? myWord : null)}
          answererName={phase === 'correct' ? displayName(lastAnswererId) : null}
          isFinalTurn={gameEndPending}
        />
      )}

      {/* 좌측: 제시어/입력 카드 + 제한시간 바 + 메인 캠 / 우측: 참가자 사이드바 (황금비 컬럼) */}
      <div className="charades-main">
        <div className="charades-stage-col">
          <div
            className={`charades-prompt-card${phase === 'correct' ? ' charades-prompt-card--correct' : ''}${phase === 'timeout' || phase === 'invalidated' ? ' charades-prompt-card--alert' : ''}${promptGhost ? ' charades-prompt-card--ghost' : ''}`}
          >
              {isPresenter ? (
                <div className="charades-prompt-card__word-wrap">
                  <span className="charades-prompt-card__label">제시어</span>
                  <p className="charades-prompt-card__word">{myWord ?? '불러오는 중...'}</p>
                  {/* 제한시간 바 — 라운드 진행 중에만 보이고, 정답/시간초과 등으로 넘어가면 자동으로 사라진다 */}
                  {phase === 'playing' && (
                    <CharadesTimeBar expiresAt={expiresAt} timeLeftSeconds={timeLeftSeconds} />
                  )}
                </div>
              ) : (
                <form
                  className="charades-prompt-card__form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (!guessText.trim()) return;
                    void submitGuess(guessText);
                    setGuessText('');
                    // 버튼 클릭으로 제출하면 포커스가 버튼으로 가버린다 — 바로 다음 답을 칠 수 있게 되돌린다.
                    inputRef.current?.focus();
                  }}
                >
                  {/* 왼쪽 열(입력창 + 제한시간 바), 오른쪽 열(제출 버튼) */}
                  <div className="charades-prompt-card__input-col">
                    <input
                      ref={inputRef}
                      className="pap-input charades-prompt-card__input"
                      value={guessText}
                      onChange={(e) => setGuessText(e.target.value)}
                      placeholder={submitDisabled ? '이번 턴은 마감됐어요' : '정답을 입력하세요'}
                      disabled={submitDisabled}
                      maxLength={GUESS_MAX_LENGTH}
                    />
                    {/* 바는 숫자를 빼고 입력창 폭을 그대로 쓴다(둘이 정확히 같은 길이) */}
                    {phase === 'playing' && (
                      <CharadesTimeBar
                        expiresAt={expiresAt}
                        timeLeftSeconds={timeLeftSeconds}
                        showClock={false}
                      />
                    )}
                  </div>
                  <div className="charades-prompt-card__submit-col">
                    <button
                      type="submit"
                      className="pap-pixel-btn charades-prompt-card__submit"
                      disabled={submitDisabled || !guessText.trim()}
                    >
                      제출
                    </button>
                    {/* 남은 시간 숫자는 제출 버튼 아래. 폭이 고정돼 있어(아래 CSS) 초가 바뀌어도
                        버튼 열 크기가 흔들리지 않는다. */}
                    {phase === 'playing' && (
                      <span className="charades-timebar__clock">{formatMmSs(timeLeftSeconds)}</span>
                    )}
                  </div>
                </form>
              )}

              {/* 시간 초과 안내는 위 CharadesResultBanner 팝업이 담당한다(카드 안에 또 쓰면 중복). */}
              {phase === 'invalidated' && (
                <p className="charades-prompt-card__note charades-prompt-card__note--warn">
                  🔌 표현자 연결이 끊겨 라운드가 무효 처리됐어요 {lastInvalidReason ? `(${lastInvalidReason})` : ''}
                </p>
            )}
          </div>

          <div className="charades-stage">
            {/* 출제자도 다른 참가자와 똑같이 자기 좌석색을 쓴다("출제자 = 골드" 규칙 없음).
                --seat을 프레임에 걸어두면 안쪽 이름표·이니셜 아바타가 같이 상속받는다. */}
            <div
              className="charades-stage__box"
              ref={stageBoxRef}
              style={presenterId ? ({ '--seat': seatColor(presenterId) } as CSSProperties) : undefined}
            >
              <div className="charades-stage__screen">
                {presenterTrack ? (
                  <ParticipantTile trackRef={presenterTrack} disableSpeakingIndicator />
                ) : (
                  <span className="charades-stage__avatar">{displayName(presenterId).slice(0, 1)}</span>
                )}
                {/* 표현자는 자기 차례에 마이크가 꺼지므로 평소엔 켜지지 않는다. 그래도 그리는 이유는
                    음소거가 실패했을 때(useCharadesMicrophone이 microphoneError로 알린다) 소리가
                    나가고 있다는 사실이 화면에도 보여야 하기 때문이다. */}
                {presenterId && <SpeakingIndicator active={speakingIds.has(presenterId)} />}
              </div>
              {/* --seat는 위 .charades-stage__box에서 상속받는다 */}
              <span className="charades-cam-name charades-cam-name--stage">
                {displayName(presenterId)}
              </span>
              <span className="charades-stage__role">👑 출제자</span>
            </div>
          </div>
        </div>

        {/* 맞추는 사람 수(2 또는 3)에 따라 캠 크기/여백을 다르게 — 3인 게임(n2)과 4인 게임(n3) 화면이 각각 다르다 */}
        <div
          className={`charades-sidebar charades-sidebar--n${otherIds.length}`}
          style={{ '--edge-shift': `${edgeShift}px` } as CSSProperties}
        >
          {otherIds.map((id) => (
            <SidebarCard
              key={id}
              trackRef={trackByIdentity.get(id)}
              name={displayName(id)}
              guess={latestGuessByParticipant[id]}
              seat={seatColor(id)}
              speaking={speakingIds.has(id)}
            />
          ))}
        </div>
      </div>

      {error && <p className="charades-error">{error}</p>}
      {confirmLeave && (
        <PixelConfirmModal
          title="방을 나가시겠습니까?"
          message="게임 중에 나가면 이번 게임 기록은 사라져요."
          confirmLabel="예"
          cancelLabel="아니오"
          tone="danger"
          onConfirm={onLeave}
          onCancel={() => setConfirmLeave(false)}
        />
      )}
    </div>
  );
}

function SidebarCard({
  trackRef,
  name,
  guess,
  seat,
  speaking,
}: {
  trackRef: TrackRef | undefined;
  name: string;
  guess?: string;
  seat: string;
  speaking: boolean;
}) {
  return (
    <div className="charades-sidebar__card" style={{ '--seat': seat } as CSSProperties}>
      <div className="charades-sidebar__cam">
        {trackRef ? (
          <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
        ) : (
          <span className="charades-sidebar__avatar">{name.slice(0, 1)}</span>
        )}
        <span className="charades-cam-name">{name}</span>
        <SpeakingIndicator active={speaking} />
      </div>
      {/* 말풍선은 캠 "밖" 카드 레벨에서 absolute로 띄운다 — 캠 자체는 overflow:hidden이라 안에 두면
          잘려서 안 보인다. 카드 위치/사이드바 세로 배치는 그대로, 캠과 다음 캠 사이 여백에 걸친다. */}
      {/* 텍스트 자르기(…)는 안쪽 span이 담당 — 말풍선 자체에 overflow:hidden을 주면 꼭지(::before/::after)가 잘린다 */}
      {guess && (
        <p className="charades-sidebar__bubble">
          <span className="charades-sidebar__bubble-text">{guess}</span>
        </p>
      )}
    </div>
  );
}

function formatMmSs(totalSeconds: number | null): string {
  if (totalSeconds === null) return '00:00';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

const RESULT_BANNER_SECONDS = RESULT_BANNER_HOLD_MS / 1000;

// 턴 결과 배너 — 화면 중앙, 카운트다운 뒤 다음 턴 이벤트(charades:turn-started)나 게임 종료
// (charades:game-ended)로 자연히 사라진다. 강제로 닫지 않고 결과 phase인 동안만 마운트되므로
// 언마운트 = 자동 종료. (useCharadesRound가 그 둘을 RESULT_BANNER_HOLD_MS만큼 붙잡아두는 것과
// 짝을 이룬다 — 그래서 표시되는 초도 같은 상수에서 가져온다.)
//
// 정답과 시간 초과가 같은 카드를 쓴다 — 둘 다 "이번 턴 제시어가 뭐였는지" 공개하는 자리다.
//
// ponytail: 점수(+N점)는 아직 백엔드가 턴 단위로 안 내려줘서(라운드 끝나야 charades:round-scored 발행) 표시 안 함 — 나중에 붙이려면 answer-revealed에 점수 필드 추가 필요.
function CharadesResultBanner({
  tone,
  word,
  answererName,
  isFinalTurn,
}: {
  tone: 'correct' | 'timeout';
  /** 이번 턴 제시어. 서버가 제시어를 찾지 못했거나 결과 이벤트를 놓쳤을 때만 null이다. */
  word: string | null | undefined;
  /** 정답일 때만 쓰는 최초 정답자 이름 */
  answererName: string | null;
  /** 마지막 턴이라 이 배너 다음이 다음 제시어가 아니라 세트 결과 화면인 경우 */
  isFinalTurn: boolean;
}) {
  const [countdown, setCountdown] = useState(RESULT_BANNER_SECONDS);
  useCountdownSound(
    countdown <= 3,
    'charades:next-prompt',
    Math.max(0, 3 - countdown),
  );

  useEffect(() => {
    if (countdown <= 1) return;
    const timer = setTimeout(() => setCountdown((c) => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown]);

  const isTimeout = tone === 'timeout';

  return (
    <div className="charades-correct-overlay">
      <div className={`charades-correct-card${isTimeout ? ' charades-correct-card--timeout' : ''}`}>
        <p className="charades-correct-card__title">{isTimeout ? '시간 초과!' : '정답!'}</p>
        {/* 정상 흐름에선 서버가 제시어를 실어주므로 위쪽 분기만 탄다. 아래는 제시어를 끝내
            못 받은 경우(미션 조회 실패, 결과 이벤트 유실)의 폴백 — 가짜 '???'로 채우지 않고
            모른다는 사실을 그대로 보여준다. */}
        {word ? (
          <p className="charades-correct-card__word">{word}</p>
        ) : (
          <p className="charades-correct-card__word charades-correct-card__word--unknown">
            {isTimeout ? '아무도 못 맞혔어요' : '???'}
          </p>
        )}
        <div className="charades-correct-card__divider" />
        <p className="charades-correct-card__names">
          {isTimeout ? '이번 턴은 점수 없이 넘어갑니다' : `${answererName ?? '???'}님 정답!`}
        </p>
        <p className="charades-correct-card__footer">
          {countdown}초 후 {isFinalTurn ? '결과가' : '다음 제시어가'} 공개됩니다
        </p>
      </div>
    </div>
  );
}

// 제한시간 바 + 남은 시간 숫자(00:56)를 한 줄에 나란히. 바 폭은 timeLeftSeconds(1초 간격 tick)를
// 그대로 쓰고 1초 linear transition으로 이어 붙여 부드럽게 줄어든다 — 예전엔 @keyframes 애니메이션
// 하나를 mount 시점 duration으로 재생했는데, 리렌더로 노드가 재사용되면 애니메이션이 안 걸려
// width:100%에 멈춰 있는 버그가 있었다. tick은 setInterval이라 백그라운드 탭에서도 계속 돈다.
function CharadesTimeBar({
  expiresAt,
  timeLeftSeconds,
  showClock = true,
}: {
  expiresAt: string | null;
  timeLeftSeconds: number | null;
  /** false면 바만 그린다 — 맞추는 사람 화면은 숫자를 제출 버튼 아래에 따로 두기 때문. */
  showClock?: boolean;
}) {
  // 서버는 종료 시각만 주므로 턴 전체 길이는 이 바가 처음 뜬 시점의 남은 시간으로 잡는다.
  const [totalSeconds, setTotalSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (!expiresAt) return;
    setTotalSeconds(Math.max(1, Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000)));
  }, [expiresAt]);

  const percent =
    totalSeconds !== null && timeLeftSeconds !== null
      ? Math.min(100, Math.max(0, (timeLeftSeconds / totalSeconds) * 100))
      : 100;

  return (
    <div className="charades-timebar-row">
      <div className="charades-timebar">
        <div className="charades-timebar__fill" style={{ width: `${percent}%` }} />
      </div>
      {showClock && <span className="charades-timebar__clock">{formatMmSs(timeLeftSeconds)}</span>}
    </div>
  );
}
