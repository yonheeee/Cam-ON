import { ParticipantTile, useLocalParticipant, useParticipants, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { roomApi } from '../../room/api/roomApi';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import { useCountdownSound } from '../../sound/hooks/useCountdownSound';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useCharadesRound } from '../hooks/useCharadesRound';
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
    chatLog,
    lastAnswererId,
    lastInvalidReason,
    expiresAt,
    timeLeftSeconds,
    gameEnded,
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
  const presenterTrack = presenterId ? trackByIdentity.get(presenterId) : undefined;
  const otherIds = rosterIds.filter((id) => id !== presenterId);

  const [guessText, setGuessText] = useState('');

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
  const showPrompt =
    phase === 'playing' || phase === 'correct' || phase === 'timeout' || phase === 'invalidated';

  return (
    <div className="charades-screen">
      <div className="charades-topbar">
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
      </div>

      {phase === 'preview' && (
        <div className="charades-preview-overlay">
          <p className="charades-preview-overlay__title">
            {isPresenter ? '다음 표현자는 나예요!' : `다음 표현자: ${displayName(presenterId)}`}
          </p>
        </div>
      )}

      {phase === 'correct' && (
        <CharadesCorrectBanner
          word={isPresenter ? myWord : (latestGuessByParticipant[lastAnswererId ?? ''] ?? null)}
          answererName={displayName(lastAnswererId)}
        />
      )}

      {/* 좌측: 제시어/입력 카드 + 제한시간 바 + 메인 캠 / 우측: 참가자 사이드바 (황금비 컬럼) */}
      <div className="charades-main">
        <div className="charades-stage-col">
          {showPrompt && (
            <div
              className={`charades-prompt-card${phase === 'correct' ? ' charades-prompt-card--correct' : ''}${phase === 'timeout' || phase === 'invalidated' ? ' charades-prompt-card--alert' : ''}`}
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
                  }}
                >
                  {/* 입력창 폭 = 제한시간 바 폭 (버튼 열은 따로 빼서 입력창 높이에만 맞춘다) */}
                  <div className="charades-prompt-card__input-col">
                    <input
                      className="pap-input charades-prompt-card__input"
                      value={guessText}
                      onChange={(e) => setGuessText(e.target.value)}
                      placeholder={submitDisabled ? '이번 턴은 마감됐어요' : '정답을 입력하세요 (20자 이내)'}
                      disabled={submitDisabled}
                      maxLength={GUESS_MAX_LENGTH}
                    />
                    {/* 시계는 버튼 열로 빼서 바가 입력창 폭을 그대로 쓰게 한다 */}
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
                    {phase === 'playing' && (
                      <span className="charades-timebar__clock">{formatMmSs(timeLeftSeconds)}</span>
                    )}
                  </div>
                </form>
              )}

              {phase === 'timeout' && (
                <p className="charades-prompt-card__note charades-prompt-card__note--warn">
                  {isPresenter ? `⏰ 시간 초과! 정답은 '${myWord ?? '???'}' 였어요` : '⏰ 시간 초과! 아무도 못 맞혔어요'}
                </p>
              )}
              {phase === 'invalidated' && (
                <p className="charades-prompt-card__note charades-prompt-card__note--warn">
                  🔌 표현자 연결이 끊겨 라운드가 무효 처리됐어요 {lastInvalidReason ? `(${lastInvalidReason})` : ''}
                </p>
              )}
            </div>
          )}

          <div className="charades-stage">
            <div className="charades-stage__box" ref={stageBoxRef}>
              <div className="charades-stage__screen">
                {presenterTrack ? (
                  <ParticipantTile trackRef={presenterTrack} disableSpeakingIndicator />
                ) : (
                  <span className="charades-stage__avatar">{displayName(presenterId).slice(0, 1)}</span>
                )}
              </div>
              <span className="charades-cam-name charades-cam-name--stage">{displayName(presenterId)}</span>
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
}: {
  trackRef: TrackRef | undefined;
  name: string;
  guess?: string;
  seat: string;
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

const CORRECT_BANNER_SECONDS = 5;

// 정답 공개 배너 — 화면 중앙, 5초 카운트다운 뒤 다음 턴 이벤트(charades:turn-started)로 자연히
// 사라진다. 강제로 닫지 않고 phase가 'correct'인 동안만 마운트되므로 언마운트 = 자동 종료.
// (useCharadesRound가 turn-started를 CORRECT_BANNER_HOLD_MS만큼 붙잡아두는 것과 짝을 이룬다 — 둘 다 5초로 맞춰둘 것.)
// ponytail: 점수(+N점)는 아직 백엔드가 턴 단위로 안 내려줘서(라운드 끝나야 charades:round-scored 발행) 표시 안 함 — 나중에 붙이려면 answer-revealed에 점수 필드 추가 필요.
function CharadesCorrectBanner({
  word,
  answererName,
}: {
  word: string | null | undefined;
  answererName: string;
}) {
  const [countdown, setCountdown] = useState(CORRECT_BANNER_SECONDS);
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

  return (
    <div className="charades-correct-overlay">
      <div className="charades-correct-card">
        <p className="charades-correct-card__title">정답!</p>
        <p className="charades-correct-card__word">{word ?? '???'}</p>
        <div className="charades-correct-card__divider" />
        <p className="charades-correct-card__names">{answererName}님 정답!</p>
        <p className="charades-correct-card__footer">{countdown}초 후 다음 제시어가 공개됩니다</p>
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
