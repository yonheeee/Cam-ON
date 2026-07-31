import { useEffect, useState } from 'react';
import { GAME_LABELS, type GameName } from '../api/courseApi';
import type { IntermissionData } from '../hooks/useCourseProgress';
import './IntermissionScreen.css';

interface IntermissionScreenProps {
  intermission: IntermissionData;
  /** 방장인가 — "바로 시작"은 방장만 누를 수 있다(서버도 검증) */
  isHost: boolean;
  /** [방장 전용] 남은 대기를 건너뛴다. 화면 전환은 서버의 game:started가 담당 */
  onSkip: () => void;
  skipping: boolean;
  skipError: string | null;
}

// 남은 대기 초. resumesAt(서버 기준 절대 시각)에서 역산하므로, 늦게 붙은 클라이언트나 이벤트가
// 늦게 도착한 클라이언트도 같은 시점에 0이 된다 — 각자 8초를 세면 서로 어긋난다.
function useRemainingSeconds(resumesAt: string): number {
  const [remaining, setRemaining] = useState(() => secondsUntil(resumesAt));
  useEffect(() => {
    setRemaining(secondsUntil(resumesAt));
    const timer = setInterval(() => setRemaining(secondsUntil(resumesAt)), 250);
    return () => clearInterval(timer);
  }, [resumesAt]);
  return remaining;
}

function secondsUntil(iso: string): number {
  const diff = new Date(iso).getTime() - Date.now();
  return Number.isNaN(diff) ? 0 : Math.max(0, Math.ceil(diff / 1000));
}

// 게임이 열리기 전 대기 화면. 코스 첫 게임 앞과 게임 사이 모두 이 화면을 쓴다 — 예전엔 첫
// 게임만 설명 없이 바로 시작했다.
//
// 다음 게임 이름·룰 설명은 서버가 MySQL games.description에서 읽어 course:intermission으로
// 실어 보낸다 — 프론트에 설명 문구를 두지 않으므로, 룰 문구를 고칠 때 배포 없이 DB만 바꾸면
// 된다(게임 타이틀 GAME_LABELS만 UI 상수).
export function IntermissionScreen({
  intermission,
  isHost,
  onSkip,
  skipping,
  skipError,
}: IntermissionScreenProps) {
  const remaining = useRemainingSeconds(intermission.resumesAt);
  const nextLabel = intermission.nextGameName
    ? (GAME_LABELS[intermission.nextGameName as GameName] ?? intermission.nextGameName)
    : null;
  // 코스 첫 게임 앞 인터미션은 아직 끝난 게임이 없어 finishedSessionSeq가 0이다 —
  // "다음 게임"이 아니라 "첫 게임"이라고 불러야 말이 된다.
  const beforeFirstGame = intermission.finishedSessionSeq === 0;

  return (
    <div className="intermission">
      <div className="intermission__card pap-pixel-card">
        <p className="intermission__title pap-pixel-title">
          {intermission.nextSessionSeq === null
            ? '모든 게임이 끝났어요! 결과를 준비하고 있어요...'
            : beforeFirstGame
              ? '첫 게임을 준비하고 있어요...'
              : '다음 게임을 준비하고 있어요...'}
        </p>

        {nextLabel && (
          <div className="intermission__next">
            <span className="intermission__next-badge pap-pixel-title">NEXT</span>
            <h2 className="intermission__next-name pap-pixel-title">{nextLabel}</h2>
            {intermission.nextRoundCount !== null && (
              <span className="intermission__next-rounds">
                {intermission.nextRoundCount}라운드
              </span>
            )}
          </div>
        )}

        {/* 룰 설명 — 서버(MySQL games.description)에서 온 값만 쓴다. 비어 있으면 칸을 아예
            그리지 않는다(프론트에 대체 문구를 두면 그게 곧 하드코딩이 된다). */}
        {intermission.nextGameDescription && (
          <div className="intermission__rules">
            <h3 className="intermission__rules-title">이렇게 해요</h3>
            <p className="intermission__rules-body">{intermission.nextGameDescription}</p>
          </div>
        )}

        <p className="intermission__countdown">
          {remaining > 0 ? `${remaining}초 후 시작` : '곧 시작해요!'}
        </p>

        {/* 대기를 줄이는 건 방 전체에 영향을 주는 진행 결정이라 방장만 누른다. 남은 게임이
            없으면(코스 종료 직전) 건너뛸 대상이 없어 서버가 skippable=false로 내려준다. */}
        {isHost && intermission.skippable && (
          <button
            type="button"
            className="pap-pixel-btn pap-pixel-btn--coral intermission__skip"
            onClick={onSkip}
            disabled={skipping}
          >
            {skipping ? '시작하는 중...' : '바로 시작'}
          </button>
        )}
        {!isHost && intermission.skippable && (
          <p className="intermission__hint">방장이 [바로 시작]을 누르면 더 빨리 시작해요</p>
        )}
        {skipError && <p className="intermission__error">{skipError}</p>}
      </div>
    </div>
  );
}
