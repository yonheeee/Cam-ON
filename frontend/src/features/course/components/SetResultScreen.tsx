import { useEffect, useState } from 'react';
import type { GameName } from '../api/courseApi';
import './SetResultScreen.css';

// 코스의 게임 한 세트가 끝날 때마다 뜨는 중간 결과.
// 위 전광판 = 방금 끝난 세트의 순위/획득 점수, 아래 컨트롤 덱 = 코스 누적 순위 + 다음 세트 안내.
// (코스의 마지막 세트까지 끝나면 이 화면이 아니라 CourseResultScreen이 뜬다)

export interface SetResultRow {
  participantId: string;
  /** 이번 세트에서 얻은 점수 (누적 아님) */
  score: number;
  rank: number;
}

export interface CourseRankRow {
  participantId: string;
  totalScore: number;
  rank: number;
  /** 이번 세트로 순위가 어떻게 움직였나 — 화살표 표시용 */
  delta: 'up' | 'down' | 'same';
}

interface SetResultScreenProps {
  /** 방금 끝난 세트 번호(1-based)와 코스 전체 세트 수 */
  setIndex: number;
  totalSets: number;
  setResult: SetResultRow[];
  courseRanking: CourseRankRow[];
  /** 방금 끝난 게임. 닌자는 게임 포인트가 없어 코스 누적 점수만 표시한다. */
  gameName: GameName | null;
  /** participantId → 닉네임. 이벤트 payload에는 id만 있고, 이름은 그릴 때 조회한다 —
   *  이벤트 수신 시점에 문자열로 박아두면 그때 아직 모르던 사람(늦게 입장 등)이 영영 "알 수 없음"이 된다 */
  nicknameById: Map<string, string>;
  /** 다음 세트 게임의 표시용 제목. null이면 다음 세트가 없다 */
  nextGameLabel: string | null;
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  /** 방장에게만 "다음 세트 시작하기" 버튼이 보인다 */
  isHost: boolean;
  /**
   * 다음 게임 소개 화면으로 넘어갈 때까지 남은 초. 방장이 안 눌러도 이 시간이 지나면 서버가
   * 룰 설명 구간을 열고(그 뒤 8초) 다음 세트를 연다 — 즉 이 카운트다운이 0이 돼도 게임이
   * 바로 시작하는 것은 아니다.
   */
  secondsLeft: number | null;
  /** [방장 전용] 지금 바로 다음 세트로. 화면 전환은 game:started가 담당한다 */
  onNext: () => void;
  /** 요청을 보내고 다음 세트가 열리기를 기다리는 중 */
  starting?: boolean;
  participantColorIndexById: ReadonlyMap<string, number>;
}

// 순위 순서대로 도는 플레이어 대표색. 등수 = 색이라 표(막대·점수·누적 카드)가 한눈에 이어진다.
const participantColor = (participantId: string, colors: ReadonlyMap<string, number>) =>
  `var(--pap-player-${colors.get(participantId) ?? 1})`;

// 점수 막대 칸 수. 1등이 꽉 차고 나머지는 1등 대비 비율로 채운다(절대 점수는 오른쪽 숫자가 말해준다).
const BAR_CELLS = 18;
const CELL_FILL_DURATION_MS = 160;
const CELL_STAGGER_MS = 45;
const ROW_STAGGER_MS = 70;

const DELTA_MARK: Record<CourseRankRow['delta'], string> = {
  up: '▲',
  down: '▼',
  same: '—',
};

const ORDINAL_LABEL = ['1st', '2nd', '3rd', '4th'];
const RACE_FLAG_ASSET = [
  '/assets/result/race-flag-teal.png',
  '/assets/result/race-flag-coral.png',
  '/assets/result/race-flag-yellow.png',
  '/assets/result/race-flag-purple.png',
];

export function SetResultScreen({
  setIndex,
  totalSets,
  setResult,
  courseRanking,
  gameName,
  nicknameById,
  nextGameLabel,
  participantId,
  isHost,
  secondsLeft,
  onNext,
  starting = false,
  participantColorIndexById,
}: SetResultScreenProps) {
  const isNinjaResult = gameName === 'NINJA';
  const topScore = Math.max(1, ...setResult.map((row) => row.score));
  const resultSignature = setResult
    .map((row) => `${row.participantId}:${row.score}`)
    .join('|');
  const maxRevealDuration =
    Math.max(0, setResult.length - 1) * ROW_STAGGER_MS +
    Math.max(0, BAR_CELLS - 1) * CELL_STAGGER_MS +
    CELL_FILL_DURATION_MS;
  const [revealElapsed, setRevealElapsed] = useState(0);

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setRevealElapsed(maxRevealDuration);
      return;
    }

    let frameId = 0;
    const startedAt = performance.now();
    const tick = (now: number) => {
      const elapsed = Math.min(maxRevealDuration, now - startedAt);
      setRevealElapsed(elapsed);
      if (elapsed < maxRevealDuration) frameId = requestAnimationFrame(tick);
    };

    setRevealElapsed(0);
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [maxRevealDuration, resultSignature]);

  const filledCellsFor = (score: number) =>
    score > 0 ? Math.max(1, Math.round((score / topScore) * BAR_CELLS)) : 0;
  const revealProgressFor = (rowIndex: number, filledCells: number) => {
    if (rowIndex < 0 || filledCells === 0) return 1;
    const rowDelay = rowIndex * ROW_STAGGER_MS;
    const rowDuration =
      Math.max(0, filledCells - 1) * CELL_STAGGER_MS + CELL_FILL_DURATION_MS;
    return Math.min(1, Math.max(0, (revealElapsed - rowDelay) / rowDuration));
  };
  // 끝내 이름을 모르는 경우는 내가 들어오기 전에 나간 사람뿐이다.
  const nicknameOf = (id: string) => nicknameById.get(id) ?? '나간 참가자';

  return (
    <div className="set-result">
      <div className="set-result__stage">
        {/* 위쪽 전광판 — 이번 세트 결과 */}
        <section
          className={`set-result__board${isNinjaResult ? ' set-result__board--podium' : ''}`}
        >
          <h1 className="set-result__board-title">{setIndex}세트 결과</h1>
          {isNinjaResult ? (
            <ol className="set-result__podium" aria-label="닌자 게임 세트 순위">
              {setResult.map((row, index) => (
                <li
                  key={row.participantId}
                  className={`set-result__podium-player set-result__podium-player--${row.rank}`}
                  style={
                    {
                      '--c': participantColor(row.participantId, participantColorIndexById),
                      '--podium-index': index,
                    } as React.CSSProperties
                  }
                >
                  <span className="set-result__podium-label">
                    <strong className="set-result__podium-name">
                      {nicknameOf(row.participantId)}
                    </strong>
                    <span className="set-result__podium-score">{row.score}p</span>
                  </span>
                  <span className="set-result__podium-step" aria-hidden>
                    <b>{['1st', '2nd', '3rd', '4th'][row.rank - 1] ?? `${row.rank}th`}</b>
                  </span>
                </li>
              ))}
            </ol>
          ) : (
          <ol className="set-result__rows">
            {setResult.map((row, index) => {
              const filled = filledCellsFor(row.score);
              const revealProgress = revealProgressFor(index, filled);
              const isRevealSettled = revealProgress >= 1;
              // 막대와 숫자는 서로 다른 애니메이션 경로(CSS / requestAnimationFrame)를 쓴다.
              // 반올림하면 마지막 칸이 아직 채워지는 중인데 숫자가 먼저 최종 점수에 도달할 수
              // 있으므로, 진행 중에는 내림하고 완전히 끝난 순간에만 실제 점수를 표시한다.
              const displayedScore = isRevealSettled
                ? row.score
                : Math.floor(row.score * revealProgress);
              return (
                <li
                  key={row.participantId}
                  className={`set-result__row${
                    row.participantId === participantId ? ' set-result__row--me' : ''
                  }${isRevealSettled ? ' set-result__row--settled' : ''}`}
                  style={
                    {
                      '--c': participantColor(row.participantId, participantColorIndexById),
                      '--row-index': index,
                    } as React.CSSProperties
                  }
                >
                  <span className="set-result__rank">{row.rank}등</span>
                  <span className="set-result__name">{nicknameOf(row.participantId)}</span>
                  <span className="set-result__bar" aria-hidden>
                    {Array.from({ length: BAR_CELLS }, (_, cell) => (
                      <i
                        key={cell}
                        className={`set-result__cell${
                          cell < filled ? ' set-result__cell--on' : ' set-result__cell--off'
                        }${
                          revealElapsed >= index * ROW_STAGGER_MS + cell * CELL_STAGGER_MS
                            ? ' set-result__cell--revealed'
                            : ''
                        }`}
                      />
                    ))}
                  </span>
                  <span
                    className={`set-result__score${
                      isRevealSettled ? ' set-result__score--settled' : ''
                    }`}
                  >
                    <span>{displayedScore}p</span>
                  </span>
                </li>
              );
            })}
          </ol>
          )}
        </section>

        {/* 아래 컨트롤 덱 — 좌: 코스 누적 순위 / 우: 다음 세트 */}
        <section className="set-result__deck">
          <div className="set-result__totals">
            <h2 className="set-result__deck-title">누적 순위</h2>
            <p className="set-result__deck-sub">순위 점수(5·3·2·1)를 누적한 현재 순위예요.</p>
            <ol className="set-result__race" aria-label="누적 순위 레이스">
              {courseRanking.map((row, index) => {
                const participantColorIndex =
                  participantColorIndexById.get(row.participantId) ?? 1;
                const setRowIndex = setResult.findIndex(
                  (setRow) => setRow.participantId === row.participantId,
                );
                const earnedScore = setRowIndex >= 0 ? setResult[setRowIndex].score : 0;
                const revealProgress = revealProgressFor(
                  setRowIndex,
                  filledCellsFor(earnedScore),
                );
                const displayedTotal =
                  row.totalScore - earnedScore + Math.round(earnedScore * revealProgress);
                return (
                  <li
                    key={row.participantId}
                    className="set-result__racer"
                    style={
                      {
                        '--c': participantColor(row.participantId, participantColorIndexById),
                        '--race-position': `${
                          13 + Math.max(0, courseRanking.length - 1 - index) * 23
                        }%`,
                        '--race-delay': `${120 + index * 90}ms`,
                      } as React.CSSProperties
                    }
                  >
                    <span className="set-result__place">{ORDINAL_LABEL[row.rank - 1] ?? `${row.rank}th`}</span>
                    <span className="set-result__flag" aria-hidden>
                      <img
                        src={RACE_FLAG_ASSET[(participantColorIndex - 1) % RACE_FLAG_ASSET.length]}
                        alt=""
                      />
                    </span>
                    <span className="set-result__racer-info">
                      <strong>{nicknameOf(row.participantId)}</strong>
                      <span>
                        {displayedTotal}점
                        <i className={`set-result__delta set-result__delta--${row.delta}`}>
                          {DELTA_MARK[row.delta]}
                        </i>
                      </span>
                    </span>
                  </li>
                );
              })}
              <li className="set-result__finish" aria-hidden />
            </ol>
          </div>

          <div
            className={`set-result__next${
              nextGameLabel === null ? ' set-result__next--final' : ''
            }`}
          >
            <div className="set-result__next-head">
              <span className="set-result__next-label">다음 세트</span>
              <span className="set-result__set-count">
                SET {setIndex} / {totalSets}
              </span>
            </div>
            <p className="set-result__next-game">{nextGameLabel ?? '마지막 세트였어요'}</p>
            {/* 방장만 넘길 수 있고, 안 눌러도 남은 시간이 0이 되면 서버가 알아서 연다.
                누른 뒤엔 다음 세트가 열릴 때까지(game:started) 준비 중 표시로 바뀐다.
                마지막 세트였다면 넘길 곳이 없다 — 곧 종합 결과가 이 화면을 대신한다. */}
            {nextGameLabel === null ? (
              <div className="set-result__final-status" role="status">
                <span className="set-result__final-copy">
                  <strong>최종 결과를 집계하고 있어요</strong>
                  <small>모든 세트의 점수를 합산하고 잠시 후 결과를 보여드려요.</small>
                </span>
              </div>
            ) : isHost ? (
              <button
                type="button"
                className="pap-pixel-btn pap-pixel-btn--primary set-result__next-btn"
                onClick={onNext}
                disabled={starting}
              >
                {starting ? '준비 중...' : `${setIndex + 1}세트 시작하기`}{' '}
                {!starting && <span aria-hidden>→</span>}
              </button>
            ) : (
              <p className="set-result__next-wait">방장이 다음 세트를 시작할 수 있어요</p>
            )}
            {secondsLeft !== null && !starting && nextGameLabel !== null && (
              <p className="set-result__next-timer">{secondsLeft}초 뒤 게임 소개로 넘어가요</p>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
