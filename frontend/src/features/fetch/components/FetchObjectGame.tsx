import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import {
  ParticipantTile,
  useLocalParticipant,
  useParticipants,
  useTracks,
} from '@livekit/components-react';
import { Track } from 'livekit-client';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { CamOffIcon } from '../../room/components/lobbyIcons';
import { RoomTopBar } from '../../room/components/RoomTopBar';
import { useSpeakingIdentities } from '../../webrtc/hooks/useSpeakingIdentities';
import { ParticipantAudioControl } from '../../webrtc/components/ParticipantAudioControl';
import { useCountdownSound } from '../../sound/hooks/useCountdownSound';
import { useFetchDetection } from '../hooks/useFetchDetection';
import type { DetectionResult } from '../api/aiApi';
import {
  COUNTDOWN_MS,
  ROUND_DURATION_MS,
  SKIP_VOTE_DELAY_MS,
  type FetchGameState,
} from '../types/fetchGame';
import './FetchObjectGame.css';

interface FetchObjectGameProps {
  state: FetchGameState;
  myNickname: string;
  /** 입장 순서대로의 participantId — 자리 배치와 색을 대기방과 똑같이 맞추는 기준 */
  joinOrder: string[];
  onReportSuccess: (
    elapsedMs: number,
    result: DetectionResult,
  ) => boolean | void | Promise<boolean | void>;
  /** 스킵 투표 (버튼/P키). 카운터 갱신은 서버 브로드캐스트가 담당한다. */
  onVoteSkip: () => void | Promise<void>;
  /** AI 인식 후 Spring 제출 단계에서 발생한 오류. 인식 오류와 구분해 화면에 보여준다. */
  submissionError?: string | null;
  /** 로고 클릭 → 확인 팝업 → 방 나가기 (확정안: 방 안에서 로고는 항상 확인 팝업 경유) */
  onLeave: () => void;
}

// 물건 가져오기 게임 화면 — 거실 배경 위에 [좌 캠열][가운데 엄마·순위표][우 캠열].
// 화면의 주인공은 제시어다: 엄마가 소리치는 말풍선이 정중앙에 있고, 카운트다운(3·2·1)도
// 같은 말풍선 안에서 일어난다(오버레이를 따로 띄우지 않는다 — 시선이 한 곳에 머문다).
// 순위표는 인원수만큼 빈칸으로 시작해 도착 순서대로 채워진다. 참가자 색(대기방에서 배정된
// 입장 순서 색)이 캠 테두리 · 닉네임 탭 · 순위 명패 세 곳에 반복돼, 색만 보고 등수를 읽을 수 있다.
export function FetchObjectGame({
  state,
  myNickname,
  joinOrder,
  onReportSuccess,
  onVoteSkip,
  submissionError,
  onLeave,
}: FetchObjectGameProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const participants = useParticipants();
  const { localParticipant, isCameraEnabled } = useLocalParticipant();
  const [confirmLeave, setConfirmLeave] = useState(false);
  const isMySuccess = (success: FetchGameState['successes'][number]) =>
    success.participantId
      ? success.participantId === localParticipant.identity
      : success.nickname === myNickname;
  const mySuccess = state.successes.find(isMySuccess);
  const myRank = state.successes.findIndex(isMySuccess);
  const hasMySuccess = mySuccess !== undefined;
  const playing = state.phase === 'playing';

  // 라운드 시계 — startedAt(방장 브로드캐스트 기준) 하나로 카운트다운/제한시간을 전부 계산
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (!playing) return;
    const timer = setInterval(() => setNowMs(Date.now()), 200);
    return () => clearInterval(timer);
  }, [playing]);
  const countdownLeft = Math.max(0, state.startedAt + COUNTDOWN_MS - nowMs);
  // 마감 시각: 첫 정답으로 서버가 단축한 값(deadlineAt)이 있으면 그것을, 없으면 기본 제한시간.
  const roundEndsAt = state.deadlineAt ?? state.startedAt + COUNTDOWN_MS + ROUND_DURATION_MS;
  const remainingMs = Math.max(0, roundEndsAt - nowMs);
  // 3·2·1 카운트다운 — 전원이 제시어를 읽고 동시에 출발 (인식도 이 동안 잠금)
  const inCountdown = playing && countdownLeft > 0;
  const countdownNumber = Math.ceil(countdownLeft / 1000);
  useCountdownSound(
    inCountdown,
    `fetch:${state.round}:${state.startedAt}`,
    (COUNTDOWN_MS - countdownLeft) / 1000,
  );
  const secondsLeft = Math.ceil(Math.min(remainingMs, ROUND_DURATION_MS) / 1000);
  // 5초 → 10초 (라운드 40초 확대와 함께): 그레이스가 남은 시간을 10초로 자르는 순간부터
  // 붉은 연출이 켜져서, "누군가 성공했다 — 서둘러!" 신호를 겸한다.
  const hurry = playing && !inCountdown && secondsLeft <= 10;

  // 내 카메라 트랙을 게임 화면의 비디오에 붙인다 (GesturePanel과 같은 패턴)
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: false }], {
    onlySubscribed: false,
  });
  const localTrack = tracks.find((t) => t.participant.isLocal)?.publication?.track;
  const speakingIds = useSpeakingIdentities();
  // attach를 effect가 아니라 callback ref에서 한다. 참가자가 나가면 좌석 배치가 재계산되며
  // 내 타일이 다른 열(<section>)로 이사할 수 있는데, React는 이를 새 <video> 생성으로 처리한다.
  // effect는 [localTrack]이 그대로라 재실행되지 않아 새 엘리먼트가 검게 남았다 — callback ref는
  // 엘리먼트가 바뀔 때마다 React가 직접 불러주므로 의존성 추측이 필요 없다.
  // (환경설정 미리보기 · 검은 타일과 같은 계열의 세 번째 사례 — attach는 callback ref가 정답)
  const localTrackRef = useRef(localTrack);
  const attachLocalVideo = useCallback(
    (video: HTMLVideoElement | null) => {
      const prev = videoRef.current;
      if (prev && localTrackRef.current) localTrackRef.current.detach(prev);
      videoRef.current = video;
      if (video && localTrack) localTrack.attach(video);
      localTrackRef.current = localTrack;
    },
    [localTrack],
  );
  // 엘리먼트는 그대로인데 트랙이 나중에 도착한 경우(카메라 늦게 켜짐)를 잇는다.
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !localTrack) return;
    localTrack.attach(video);
    localTrackRef.current = localTrack;
    return () => {
      localTrack.detach(video);
    };
  }, [localTrack]);

  // ROI 인식 루프 — 카운트다운 끝 + 아직 성공 전 + 카메라 켜짐일 때만
  // (캠이 꺼지면 검은 프레임만 가니 전송 자체를 멈춘다)
  const { lastResult, streak, requiredStreak, error, latencyMs } = useFetchDetection({
    videoRef,
    target: state.target,
    active: playing && !inCountdown && !mySuccess && isCameraEnabled,
    onSuccess: onReportSuccess,
  });

  // 성공 순간 셀레브레이션 — 잠깐 크게 띄웠다가 사라진다
  const [celebrating, setCelebrating] = useState(false);
  const [otherSuccess, setOtherSuccess] = useState<
    FetchGameState['successes'][number] | null
  >(null);
  const observedRoundRef = useRef(state.round);
  const observedSuccessCountRef = useRef(state.successes.length);

  useEffect(() => {
    if (!hasMySuccess) {
      setCelebrating(false);
      return;
    }
    setCelebrating(true);
    const timer = setTimeout(() => setCelebrating(false), 1800);
    return () => clearTimeout(timer);
  }, [hasMySuccess]);

  useEffect(() => {
    if (observedRoundRef.current !== state.round) {
      observedRoundRef.current = state.round;
      observedSuccessCountRef.current = state.successes.length;
      setOtherSuccess(null);
      return;
    }

    if (state.successes.length <= observedSuccessCountRef.current) {
      observedSuccessCountRef.current = state.successes.length;
      return;
    }

    observedSuccessCountRef.current = state.successes.length;
    const latestSuccess = state.successes[state.successes.length - 1];
    if (latestSuccess.participantId === localParticipant.identity) return;

    setOtherSuccess(latestSuccess);
    const timer = setTimeout(() => setOtherSuccess(null), 2200);
    return () => clearTimeout(timer);
  }, [state.round, state.successes, localParticipant.identity]);

  const nearMatch = streak > 0;
  const recognitionAccepted = streak >= requiredStreak && !mySuccess;

  // 자리와 색은 대기방의 입장 순서를 그대로 쓴다 — 대기방에서 노란색이던 사람이 게임에서
  // 보라색이 되면 "노란 사람이 1등!" 같은 말이 안 통한다. 스냅샷에 아직 없는 참가자(방금
  // 들어온 사람)는 뒤로 밀고, 스냅샷 자체가 비어 있으면 identity 정렬로 떨어뜨린다.
  // (identity 정렬도 전 클라이언트에서 같은 결과라 자리만은 항상 일치한다)
  const seats = useMemo(() => {
    const orderOf = (identity: string) => {
      const index = joinOrder.indexOf(identity);
      return index < 0 ? Number.MAX_SAFE_INTEGER : index;
    };
    return [...participants].sort(
      (a, b) =>
        orderOf(a.identity) - orderOf(b.identity) || a.identity.localeCompare(b.identity),
    );
  }, [participants, joinOrder]);
  // 짝수 자리는 왼쪽, 홀수 자리는 오른쪽 — 2인은 1:1, 3인은 2:1, 4인은 2:2가 된다.
  const leftSeats = seats.filter((_, i) => i % 2 === 0);
  const rightSeats = seats.filter((_, i) => i % 2 === 1);
  const colorOf = (identity: string) => {
    const index = joinOrder.indexOf(identity);
    return ((index < 0 ? seats.findIndex((s) => s.identity === identity) : index) % 4) + 1;
  };

  // ---- 스킵 투표 ----
  // 첫 성공 전에만 의미가 있다: 성공자가 나오면 그레이스(10초)가 라운드를 곧 닫으므로
  // 버튼을 숨긴다. 노출 지연(5초)은 제시어를 보고 주변을 훑을 최소 시간 — 반사 스킵 방지.
  const iVotedSkip = state.skipVotes.includes(localParticipant.identity);
  const skipVisible =
    playing &&
    !inCountdown &&
    state.successes.length === 0 &&
    nowMs >= state.startedAt + COUNTDOWN_MS + SKIP_VOTE_DELAY_MS;
  // 분모는 투표 이벤트의 required(서버 원본)가 우선. 아직 아무도 안 눌렀으면(이벤트 없음)
  // 현재 접속자 수로 표시만 하고, 판정은 어차피 서버가 한다.
  const skipRequired = state.skipRequired || seats.length;

  useEffect(() => {
    if (!skipVisible || iVotedSkip) return;
    const onKeyDown = (event: KeyboardEvent) => {
      // e.code는 자판 배열 무관 물리 키 — 한글 IME 상태에서도 P 키를 잡는다.
      if (event.code !== 'KeyP') return;
      // 채팅 입력 중 타이핑을 투표로 오인하지 않는다.
      const target = event.target as HTMLElement | null;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target?.isContentEditable
      ) {
        return;
      }
      void onVoteSkip();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [skipVisible, iVotedSkip, onVoteSkip]);

  const trackByIdentity = new Map(tracks.map((t) => [t.participant.identity, t]));
  const successIndexOf = (seat: (typeof seats)[number]) =>
    state.successes.findIndex((success) =>
      success.participantId
        ? success.participantId === seat.identity
        : success.nickname === (seat.name ?? ''),
    );

  const hint = mySuccess
    ? `${myRank + 1}등 · ${(mySuccess.elapsedMs / 1000).toFixed(1)}s 🎉`
    : !isCameraEnabled
      ? '카메라를 켜야 참여할 수 있어요'
      : submissionError
        ? `⚠ ${submissionError}`
        : error
          ? `⚠ ${error}`
          : recognitionAccepted
            ? '인식 성공! 서버 확인 중'
            : nearMatch
              ? `거의 다 왔어요 ${streak}/${requiredStreak}`
              : lastResult?.detectedValue
                ? `인식됨: ${lastResult.detectedValue}`
                : '물건을 네모 안에!';

  const renderSeat = (seat: (typeof seats)[number]) => {
    const seatNickname = seat.name ?? '';
    const rank = successIndexOf(seat);
    const done = rank >= 0;
    const isMe = seat.isLocal;
    const trackRef = trackByIdentity.get(seat.identity);
    return (
      <div
        key={seat.identity}
        className={`fetch-seat fetch-seat--p${colorOf(seat.identity)}${
          done ? ' fetch-seat--done' : ''
        }${speakingIds.has(seat.identity) ? ' fetch-seat--speaking' : ''}`}
      >
        <div className="fetch-seat__cam participant-audio-host">
          {isMe ? (
            <video ref={attachLocalVideo} autoPlay playsInline muted className="fetch-seat__video" />
          ) : trackRef ? (
            <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
          ) : null}

          {/* 인식 영역은 네 귀퉁이 브래킷으로 — 점선 상자보다 화면을 덜 가린다 */}
          {isMe && isCameraEnabled && !mySuccess && (
            <span
              className={`fetch-seat__aim${
                recognitionAccepted
                  ? ' fetch-seat__aim--hit'
                  : nearMatch
                    ? ' fetch-seat__aim--near'
                    : ''
              }`}
            />
          )}
          {(isMe ? !isCameraEnabled : !trackRef) && (
            <span className="fetch-seat__off">
              <span className="fetch-seat__off-icon">{CamOffIcon}</span>
              카메라가 꺼져 있어요
            </span>
          )}
          {done && <span className="fetch-seat__stamp">{rank + 1}위</span>}
          <ParticipantAudioControl identity={seat.identity} />
          {/* 닉네임 배지 — 캠 좌측 상단 픽셀 스티커. 닌자와 같은 방식 */}
          <span className={`fetch-seat__tag${isMe ? ' fetch-seat__tag--me' : ''}`}>
            {isMe ? myNickname : seatNickname}
          </span>
        </div>

        {isMe && (
          <span className="fetch-seat__hint" role="status" aria-live="polite">
            {hint}
            {/* 진단용: 왕복 지연 + 제시어 점수 — dev 빌드에서만 (임계값 튜닝용) */}
            {import.meta.env.DEV && latencyMs !== null && ` · ${latencyMs}ms`}
            {import.meta.env.DEV &&
              lastResult?.targetScore != null &&
              ` · ${lastResult.targetScore.toFixed(2)}`}
          </span>
        )}
      </div>
    );
  };

  return (
    <div
      className={`fetch-game${inCountdown ? ' fetch-game--count' : ''}${
        hurry ? ' fetch-game--hurry' : ''
      }`}
    >
      <RoomTopBar
        musicSource="/assets/sounds/find-thing.mp3"
        className="fetch-game__head"
        onRequestLeave={() => setConfirmLeave(true)}
      />

      <div className="fetch-game__floor">
        <section className="fetch-game__side">{leftSeats.map(renderSeat)}</section>

        <section className="fetch-game__center">
          {/* 라운드·남은 시간 — 말풍선 바로 위 가운데 */}
          <div className="fetch-game__meter">
            <span className="fetch-game__round pap-pixel-title">
              R{state.round}/{state.totalRounds}
            </span>
            <span className="fetch-game__clock pap-pixel-title">
              {secondsLeft}
              <small>s</small>
            </span>
          </div>

          {/* 말풍선이 위, 엄마가 아래 — 꼬리가 엄마 머리를 가리킨다 */}
          <div className="fetch-game__caller">
            <CallerSprite />
            <div className="fetch-game__bubble">
              {inCountdown ? (
                <span key={countdownNumber} className="fetch-game__count pap-pixel-title">
                  {countdownNumber}
                </span>
              ) : celebrating && mySuccess ? (
                /* 내 성공 순간은 전체화면 오버레이 대신 말풍선 안에서 — 제시어와 겹치지 않는다 */
                <span className="fetch-game__cheer pap-pixel-title">{myRank + 1}등! 🎉</span>
              ) : (
                <span className="fetch-game__shout">
                  엄마! 내{' '}
                  <span className="fetch-game__word">
                    <span className="pap-pixel-title">{state.target}</span>
                  </span>
                  <br />
                  어딨어??
                </span>
              )}
            </div>
          </div>

          <ol className="fetch-game__board">
            <li className="fetch-game__board-head">순위</li>
            {seats.map((_, index) => {
              const success = state.successes[index];
              const owner = success
                ? seats.find((seat) =>
                    success.participantId
                      ? seat.identity === success.participantId
                      : (seat.name ?? '') === success.nickname,
                  )
                : undefined;
              return (
                <li
                  key={index}
                  className={
                    success
                      ? `fetch-slot fetch-slot--filled fetch-seat--p${
                          owner ? colorOf(owner.identity) : 1
                        }`
                      : 'fetch-slot fetch-slot--empty'
                  }
                >
                  <span className="fetch-slot__no pap-pixel-title">{index + 1}</span>
                  <span className="fetch-slot__who">{success ? success.nickname : ''}</span>
                  <span className="fetch-slot__time pap-pixel-title">
                    {success ? `+${success.score}p` : '--'}
                  </span>
                </li>
              );
            })}
          </ol>

          {/* 스킵 투표 — 첫 성공 전 + 노출 지연 뒤에만. 전원이 누르면 서버가 라운드를 닫는다. */}
          {skipVisible && (
            <button
              type="button"
              className={`fetch-game__skip pap-pixel-btn${
                iVotedSkip ? ' fetch-game__skip--voted' : ''
              }`}
              disabled={iVotedSkip}
              onClick={() => void onVoteSkip()}
            >
              {iVotedSkip ? '스킵 대기 중' : '못 찾겠어요'}{' '}
              <b className="pap-pixel-title">
                {state.skipVotes.length}/{skipRequired}
              </b>
              {!iVotedSkip && <small> (P)</small>}
            </button>
          )}
        </section>

        <section className="fetch-game__side">{rightSeats.map(renderSeat)}</section>
      </div>

      {/* 라운드 결과 팝업 */}
      {state.phase === 'roundResult' && (
        <div className="pap-modal-backdrop">
          <div className="pap-modal">
            <div className="fetch-game__result pap-pixel-card">
              <h2 className="pap-pixel-title">
                {state.roundSkipped
                  ? `라운드 ${state.round} 스킵!`
                  : `라운드 ${state.round} 종료!`}
              </h2>
              <ol>
                {state.successes.map((s, i) => (
                  <li key={s.nickname}>
                    {i + 1}위 — {s.nickname} ({(s.elapsedMs / 1000).toFixed(1)}s)
                  </li>
                ))}
                {state.successes.length === 0 && (
                  <li>
                    {state.roundSkipped
                      ? '모두가 스킵에 동의했어요 🏳️'
                      : '성공자 없음 😢'}
                  </li>
                )}
              </ol>
              <p className="fetch-game__wait">잠시 후 다음 라운드가 시작돼요...</p>
            </div>
          </div>
        </div>
      )}

      {/* 세트가 끝난 뒤 순위 발표는 코스 공통 중간 결과 화면(SetResultScreen)이 한다 —
          여기서 팝업을 또 띄우면 두 겹으로 겹친다(닌자도 같은 이유로 자체 종료 화면을 없앴다). */}

      {/* 타임아웃 — 못 찾은 사람에게만 (성공자는 이미 기록이 있으니) */}
      {playing && !inCountdown && remainingMs <= 0 && !mySuccess && (
        <div className="fetch-game__flash fetch-game__flash--fail">
          <span className="pap-pixel-title">시간 초과! ⏰</span>
        </div>
      )}

      {/* AI 통과 후 Spring 순위 확정을 기다리는 구간은 오버레이를 띄우지 않는다 — 곧바로
          말풍선에 등수가 뜨므로 중간 안내가 화면만 가린다(상태는 캠 아래 힌트에 남아 있다). */}

      {otherSuccess && (
        <div className="fetch-game__toast" role="status" aria-live="polite">
          <b className="pap-pixel-title">{otherSuccess.nickname}</b>
          {otherSuccess.rank}위 · +{otherSuccess.score}점
        </div>
      )}

      {/* 내 성공 셀레브레이션은 말풍선 안에서 한다 (위 fetch-game__cheer) */}

      {confirmLeave && (
        <PixelConfirmModal
          title="정말 방을 나갈까요?"
          message="현재 방과 게임 결과에서 나가 메인 화면으로 이동해요."
          confirmLabel="방 나가기"
          cancelLabel="취소"
          tone="danger"
          onConfirm={onLeave}
          onCancel={() => setConfirmLeave(false)}
        />
      )}
    </div>
  );
}

// 소리치는 엄마 — 좌우 대칭이라 왼쪽 12칸만 정의하고 미러링한다(24×28 도트).
// K 외곽선 · H 머리 · S 피부 · D 손(그늘) · M 벌린 입 · T 상의 · P 하의 · B 신발
const CALLER_HALF = [
  '....KKKKKKKK', '..KKHHHHHHHH', '..KHHHHHHHHH', '..KHHHHHHHHH',
  '..KHHHSSSSSS', '..KHSSSSSSSS', '..KSSSSKKSSS', '..KSSSSKKSSS',
  '..KSSSSSSSSS', '..KKDDDDSKMM', '..KKDDDDKMMM', '..KKDDDDKMMM',
  '..KKDDDDSKMM', '...KKDDDKSSS', '....KKKKKSSS', '...KSSSKKSSS',
  '...KSSSKTTTT', '...KSSSKTTTT', '...KSSSKTTTT', '...KTTTKTTTT',
  '...KTTTTTTTT', '...KTTTTTTTT', '...KTTTTTTTT', '...KTTTTTTTT',
  '....KPPPPPPP', '....KPPPPPKK', '....KPPPPPKK', '....KBBBBBKK',
];
const CALLER_PALETTE: Record<string, string> = {
  K: '#2b1833', H: '#4a2d1c', S: '#ffd0a6', D: '#eeae7c',
  M: '#7d1f38', T: '#c94f3d', P: '#3a2c3f', B: '#2b1833',
};
const CALLER_PX = 4;

function CallerSprite() {
  const rects: ReactElement[] = [];
  CALLER_HALF.forEach((half, y) => {
    const row = half + [...half].reverse().join('');
    let x = 0;
    while (x < row.length) {
      const color = row[x];
      let run = 1;
      while (row[x + run] === color) run += 1;
      if (color !== '.') {
        rects.push(
          <rect
            key={`${x}-${y}`}
            x={x * CALLER_PX}
            y={y * CALLER_PX}
            width={run * CALLER_PX}
            height={CALLER_PX}
            fill={CALLER_PALETTE[color]}
          />,
        );
      }
      x += run;
    }
  });
  const width = 24 * CALLER_PX;
  const height = CALLER_HALF.length * CALLER_PX;
  return (
    <svg
      className="fetch-game__caller-sprite"
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      shapeRendering="crispEdges"
      role="img"
      aria-label="손을 입에 모으고 소리치는 엄마"
    >
      {rects}
    </svg>
  );
}
