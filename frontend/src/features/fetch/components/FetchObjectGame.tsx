import { useEffect, useRef, useState } from 'react';
import {
  ParticipantTile,
  useLocalParticipant,
  useParticipants,
  useTracks,
} from '@livekit/components-react';
import { Track } from 'livekit-client';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { CamOffIcon, CamOnIcon, MicOffIcon, MicOnIcon } from '../../room/components/lobbyIcons';
import { useFetchDetection } from '../hooks/useFetchDetection';
import { COUNTDOWN_MS, ROUND_DURATION_MS, type FetchGameState } from '../hooks/useFetchGame';
import './FetchObjectGame.css';

interface FetchObjectGameProps {
  state: FetchGameState;
  myNickname: string;
  onReportSuccess: (elapsedMs: number) => void;
  /** 라운드 마감/다음/퇴장 콜백 — /dev/fetch mock(방장 주도)에서만 넘긴다.
   *  안 넘기면 "서버 주도" 모드: 진행은 백엔드가 하므로 진행 버튼 대신 대기 문구를 보여준다. */
  onEndRound?: () => void;
  onNextRound?: () => void;
  onExit?: () => void;
  /** 로고 클릭 → 확인 팝업 → 방 나가기 (확정안: 방 안에서 로고는 항상 확인 팝업 경유) */
  onLeave: () => void;
}

// 물건 가져오기 게임 화면 — 로비와 같은 골격(비디오 그리드 + 우측 사이드바).
// 상단 바: 로고 · 제시어 · 라운드/타이머 / 순위 로그는 타일 배지 + 성공 칩 + 결과 팝업으로.
export function FetchObjectGame({
  state,
  myNickname,
  onReportSuccess,
  onEndRound,
  onNextRound,
  onExit,
  onLeave,
}: FetchObjectGameProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const participants = useParticipants();
  // 게임 중에도 캠/마이크는 끌 수 있어야 한다 (로비와 동일한 토글)
  const { localParticipant, isCameraEnabled, isMicrophoneEnabled } = useLocalParticipant();
  const [confirmLeave, setConfirmLeave] = useState(false);
  // 게임을 시작한 방장이 중간에 나가면 라운드 진행(마감/다음)이 멈춰 무한 대기가 된다 —
  // 방장이 방에 없으면 입장 순서(P1→P2→...)상 가장 앞선 참가자가 진행권을 이어받는다.
  // joinedAt은 LiveKit 서버 기준 시각이라 모든 클라이언트가 같은 순서를 본다.
  // 대기방의 방장 연쇄 위임(입장 순서 연쇄)과 동일한 규칙 — course 도메인 생기면 Spring이 담당.
  const presentNames = [...participants]
    .sort((a, b) => (a.joinedAt?.getTime() ?? 0) - (b.joinedAt?.getTime() ?? 0))
    .map((p) => p.name ?? '')
    .filter(Boolean);
  const hostPresent = state.hostNickname !== null && presentNames.includes(state.hostNickname);
  const actingHost = hostPresent ? state.hostNickname : presentNames[0] ?? myNickname;
  const isHost = actingHost === myNickname;
  const mySuccess = state.successes.find((s) => s.nickname === myNickname);
  const myRank = state.successes.findIndex((s) => s.nickname === myNickname);
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
  const goFlash = playing && countdownLeft <= 0 && nowMs - (state.startedAt + COUNTDOWN_MS) < 700;

  // 내 카메라 트랙을 게임 화면의 비디오에 붙인다 (GesturePanel과 같은 패턴)
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: false }], {
    onlySubscribed: false,
  });
  const localTrack = tracks.find((t) => t.participant.isLocal)?.publication?.track;
  const remoteTracks = tracks.filter((t) => !t.participant.isLocal);
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !localTrack) return;
    localTrack.attach(video);
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
  useEffect(() => {
    if (!mySuccess) {
      setCelebrating(false);
      return;
    }
    setCelebrating(true);
    const timer = setTimeout(() => setCelebrating(false), 1800);
    return () => clearTimeout(timer);
  }, [!!mySuccess]);

  // [방장] 라운드 마감 판단. 같은 라운드 중복 마감은 라운드 번호로 가드.
  // 두 경우를 분리한 이유: 전원 성공 마감은 셀레브레이션(1.8초)이 결과 팝업에 덮이지 않게
  // 2초 여유를 주고, 타임아웃 마감은 즉시. (remainingMs는 200ms마다 바뀌어서 타이머를 거는
  // effect에 넣으면 계속 리셋되므로 effect를 둘로 쪼갠다)
  const endedRoundRef = useRef(0);

  // 케이스 1: 타임아웃 — "시간 초과!" 연출이 보일 시간(1.5초)을 주고 마감.
  // (remainingMs는 0에 도달하면 그대로 0에 머물러서 타이머가 리셋되지 않는다)
  // 서버 주도 모드(onEndRound 없음)에서는 백엔드 타임아웃이 round:end를 밀어주므로 하지 않는다.
  useEffect(() => {
    if (!onEndRound || !isHost || !playing || endedRoundRef.current === state.round) return;
    if (remainingMs > 0) return;
    const timer = setTimeout(() => {
      if (endedRoundRef.current === state.round) return;
      endedRoundRef.current = state.round;
      onEndRound();
    }, 1500);
    return () => clearTimeout(timer);
  }, [isHost, playing, state.round, remainingMs, onEndRound]);

  // 케이스 2: 전원 성공 — 마지막 성공자의 셀레브레이션이 끝날 시간을 주고 마감
  useEffect(() => {
    if (!onEndRound || !isHost || !playing || endedRoundRef.current === state.round) return;
    if (state.successes.length < participants.length) return;
    const timer = setTimeout(() => {
      if (endedRoundRef.current === state.round) return;
      endedRoundRef.current = state.round;
      onEndRound();
    }, 2000);
    return () => clearTimeout(timer);
  }, [isHost, playing, state.round, state.successes.length, participants.length, onEndRound]);

  const nearMatch = streak > 0;
  const totalTiles = 1 + remoteTracks.length;
  const ranking = Object.entries(state.totals).sort((a, b) => b[1] - a[1]);
  // 누적 선두 — 타일 이름 바에 왕관으로 표시
  const leader = ranking.length > 0 && ranking[0][1] > 0 ? ranking[0][0] : null;
  const scoreOf = (nickname: string) => state.totals[nickname] ?? 0;
  const hint = mySuccess
    ? `${myRank + 1}등 · ${(mySuccess.elapsedMs / 1000).toFixed(1)}s 🎉`
    : inCountdown
      ? '제시어를 확인하세요!'
      : !isCameraEnabled
        ? '카메라를 켜야 참여할 수 있어요!'
        : error
          ? `⚠ ${error}`
          : nearMatch
            ? `거의 다 왔어요! (${streak}/${requiredStreak})`
            : lastResult?.detectedValue
              ? `인식됨: ${lastResult.detectedValue}`
              : '물건을 박스 안에!';

  return (
    <div className="fetch-game">
      {/* 상단 바 — 로비 헤더와 같은 결: 로고 · 제시어 · 라운드/타이머 */}
      <header className="fetch-game__header">
        <img
          className="fetch-game__logo pap-pixel-img"
          src="/assets/cam-on-logo.png"
          alt="CAM, ON!"
          onClick={() => setConfirmLeave(true)}
        />
        <div className="fetch-game__mission">
          <span className="fetch-game__mission-label">가져올 물건</span>
          <span className="fetch-game__target pap-pixel-title">{state.target}</span>
        </div>
        <div className="fetch-game__status">
          <span className="fetch-game__round pap-pixel-title">
            R{state.round}/{state.totalRounds}
          </span>
          <span
            className={`fetch-game__timer pap-pixel-title${
              !inCountdown && remainingMs <= 5000 ? ' fetch-game__timer--danger' : ''
            }`}
          >
            {/* 카운트다운 동안은 제한시간을 풀로 고정 표시 (23s처럼 보이지 않게) */}
            {Math.ceil(Math.min(remainingMs, ROUND_DURATION_MS) / 1000)}s
          </span>
        </div>
      </header>

      <div className="fetch-game__body">
        {/* 비디오 그리드 — 로비와 동일 골격 */}
        <section
          className={`fetch-game__grid${totalTiles === 3 ? ' fetch-game__grid--3' : ''}`}
        >
          {/* 내 타일: ROI + 판정 힌트 */}
          <div
            className={`fetch-game__tile fetch-game__tile--me${
              mySuccess ? ' fetch-game__tile--done' : ''
            }`}
          >
            <video ref={videoRef} autoPlay playsInline muted className="fetch-game__video" />
            {/* ROI는 카메라가 켜져 있을 때만 — 꺼진 검은 화면 위 박스는 어색하다 */}
            {isCameraEnabled && (
              <div
                className={`fetch-game__roi${nearMatch || mySuccess ? ' fetch-game__roi--hot' : ''}`}
              />
            )}
            {!isCameraEnabled && !mySuccess && (
              <div className="fetch-game__cam-off">
                <span className="fetch-game__cam-off-icon">{CamOffIcon}</span>
                <span>카메라가 꺼져 있어요</span>
              </div>
            )}
            {mySuccess && (
              <span className="fetch-game__badge pap-pixel-title">{myRank + 1}위!</span>
            )}
            <div className="fetch-game__tile-bar">
              <span className="fetch-game__tile-name">
                {leader === myNickname && '👑 '}
                {myNickname}
              </span>
              <span className="fetch-game__score pap-pixel-title">{scoreOf(myNickname)}점</span>
              <span className="fetch-game__controls">
                <button
                  type="button"
                  className={`fetch-game__control${isCameraEnabled ? '' : ' fetch-game__control--off'}`}
                  onClick={() => void localParticipant.setCameraEnabled(!isCameraEnabled)}
                  title={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                  aria-label={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                >
                  {isCameraEnabled ? CamOnIcon : CamOffIcon}
                </button>
                <button
                  type="button"
                  className={`fetch-game__control${isMicrophoneEnabled ? '' : ' fetch-game__control--off'}`}
                  onClick={() => void localParticipant.setMicrophoneEnabled(!isMicrophoneEnabled)}
                  title={isMicrophoneEnabled ? '마이크 끄기' : '마이크 켜기'}
                  aria-label={isMicrophoneEnabled ? '마이크 끄기' : '마이크 켜기'}
                >
                  {isMicrophoneEnabled ? MicOnIcon : MicOffIcon}
                </button>
              </span>
              <span className="fetch-game__hint">
                {hint}
                {/* 진단용: 왕복 지연 + 제시어 점수 — dev 빌드에서만 (임계값 튜닝용) */}
                {import.meta.env.DEV && latencyMs !== null && ` · ${latencyMs}ms`}
                {import.meta.env.DEV &&
                  lastResult?.targetScore != null &&
                  ` · ${lastResult.targetScore.toFixed(2)}`}
              </span>
            </div>
          </div>

          {/* 다른 참가자 타일 */}
          {remoteTracks.map((trackRef) => {
            const nickname = trackRef.participant.name ?? '';
            const successIndex = state.successes.findIndex((s) => s.nickname === nickname);
            const done = successIndex >= 0;
            return (
              <div
                key={trackRef.participant.identity}
                className={`fetch-game__tile${done ? ' fetch-game__tile--done' : ''}`}
              >
                <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
                {done && (
                  <span className="fetch-game__badge pap-pixel-title">{successIndex + 1}위!</span>
                )}
                <div className="fetch-game__tile-bar">
                  <span className="fetch-game__tile-name">
                    {leader === nickname && '👑 '}
                    {nickname}
                  </span>
                  <span className="fetch-game__score pap-pixel-title">{scoreOf(nickname)}점</span>
                  {done && (
                    <span className="fetch-game__rank">
                      {successIndex + 1}등 · {(state.successes[successIndex].elapsedMs / 1000).toFixed(1)}s
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </section>

        {/* 게임 중 채팅은 없음 — 물건 찾느라 바쁘고, 화상 크기를 최대로 확보하기 위함.
            채팅이 필요한 대화는 라운드 결과/로비에서. */}
      </div>

      {/* 라운드 결과 팝업 */}
      {state.phase === 'roundResult' && (
        <div className="pap-modal-backdrop">
          <div className="pap-modal">
            <div className="fetch-game__result pap-pixel-card">
              <h2 className="pap-pixel-title">라운드 {state.round} 종료!</h2>
              <ol>
                {state.successes.map((s, i) => (
                  <li key={s.nickname}>
                    {i + 1}위 — {s.nickname} ({(s.elapsedMs / 1000).toFixed(1)}s)
                  </li>
                ))}
                {state.successes.length === 0 && <li>성공자 없음 😢</li>}
              </ol>
              {!onNextRound ? (
                <p className="fetch-game__wait">잠시 후 다음 라운드가 시작돼요...</p>
              ) : isHost ? (
                <button
                  type="button"
                  className="pap-pixel-btn pap-pixel-btn--primary"
                  onClick={() => void onNextRound()}
                >
                  다음 라운드
                </button>
              ) : (
                <p className="fetch-game__wait">방장이 다음 라운드를 시작하길 기다리는 중...</p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 최종 결과 팝업 */}
      {state.phase === 'ended' && (
        <div className="pap-modal-backdrop">
          <div className="pap-modal">
            <div className="fetch-game__result pap-pixel-card">
              <h2 className="pap-pixel-title">🏆 최종 결과</h2>
              <ol>
                {ranking.map(([nickname, score], i) => (
                  <li key={nickname} className={i === 0 ? 'fetch-game__winner' : ''}>
                    {i + 1}위 — {nickname} ({score}점)
                  </li>
                ))}
              </ol>
              {!onExit ? (
                <p className="fetch-game__wait">잠시 후 다음 게임으로 넘어가요...</p>
              ) : isHost ? (
                <button type="button" className="pap-pixel-btn pap-pixel-btn--primary" onClick={onExit}>
                  대기방으로
                </button>
              ) : (
                <p className="fetch-game__wait">곧 대기방으로 돌아갑니다...</p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 3·2·1 카운트다운 → GO! (제시어 읽는 시간 + 인식 팁) */}
      {inCountdown && (
        <div className="fetch-game__countdown">
          <span key={Math.ceil(countdownLeft / 1000)} className="pap-pixel-title">
            {Math.ceil(countdownLeft / 1000)}
          </span>
          <span className="fetch-game__tip">
            Tip. 물건이 잘 안 잡히면 다양한 각도로 돌려보세요!
          </span>
        </div>
      )}
      {goFlash && (
        <div className="fetch-game__countdown fetch-game__countdown--go">
          <span className="pap-pixel-title">GO!</span>
        </div>
      )}

      {/* 타임아웃 — 못 찾은 사람에게만 (성공자는 이미 기록이 있으니) */}
      {playing && !inCountdown && remainingMs <= 0 && !mySuccess && (
        <div className="fetch-game__timeout">
          <span className="pap-pixel-title">시간 초과! ⏰</span>
        </div>
      )}

      {/* 내 성공 셀레브레이션 */}
      {celebrating && mySuccess && (
        <div className="fetch-game__celebrate">
          <span className="pap-pixel-title">{myRank + 1}등! 🎉</span>
          <span className="fetch-game__celebrate-time">
            {(mySuccess.elapsedMs / 1000).toFixed(1)}초
          </span>
        </div>
      )}

      {confirmLeave && (
        <PixelConfirmModal
          title="정말 방을 나갈까요?"
          message="게임 중에 나가면 이번 게임 기록은 사라져요."
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
