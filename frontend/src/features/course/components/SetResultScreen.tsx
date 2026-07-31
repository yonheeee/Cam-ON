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
  /** participantId → 닉네임. 이벤트 payload에는 id만 있고, 이름은 그릴 때 조회한다 —
   *  이벤트 수신 시점에 문자열로 박아두면 그때 아직 모르던 사람(늦게 입장 등)이 영영 "알 수 없음"이 된다 */
  nicknameById: Map<string, string>;
  /** 다음 세트 게임의 표시용 제목. null이면 다음 세트가 없다 */
  nextGameLabel: string | null;
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  /** 방장에게만 "다음 세트 시작하기" 버튼이 보인다 */
  isHost: boolean;
  /** 자동 시작까지 남은 초. 방장이 안 눌러도 이 시간이 지나면 서버가 다음 세트를 연다 */
  secondsLeft: number | null;
  /** [방장 전용] 지금 바로 다음 세트로. 화면 전환은 game:started가 담당한다 */
  onNext: () => void;
  /** 요청을 보내고 다음 세트가 열리기를 기다리는 중 */
  starting?: boolean;
}

// 순위 순서대로 도는 플레이어 대표색. 등수 = 색이라 표(막대·점수·누적 카드)가 한눈에 이어진다.
const RANK_COLORS = [
  'var(--pap-festival-coral)',
  'var(--pap-play-yellow)',
  'var(--pap-arcade-teal)',
  'var(--pap-lavender)',
];

// 점수 막대 칸 수. 1등이 꽉 차고 나머지는 1등 대비 비율로 채운다(절대 점수는 오른쪽 숫자가 말해준다).
const BAR_CELLS = 18;

const DELTA_MARK: Record<CourseRankRow['delta'], string> = {
  up: '▲',
  down: '▼',
  same: '—',
};

export function SetResultScreen({
  setIndex,
  totalSets,
  setResult,
  courseRanking,
  nicknameById,
  nextGameLabel,
  participantId,
  isHost,
  secondsLeft,
  onNext,
  starting = false,
}: SetResultScreenProps) {
  const topScore = Math.max(1, ...setResult.map((row) => row.score));
  // 끝내 이름을 모르는 경우는 내가 들어오기 전에 나간 사람뿐이다.
  const nicknameOf = (id: string) => nicknameById.get(id) ?? '나간 참가자';

  return (
    <div className="set-result">
      <div className="set-result__stage">
        {/* 위쪽 전광판 — 이번 세트 결과 */}
        <section className="set-result__board">
          <h1 className="set-result__board-title">{setIndex}세트 결과</h1>
          <ol className="set-result__rows">
            {setResult.map((row, index) => {
              const filled = row.score > 0
                ? Math.max(1, Math.round((row.score / topScore) * BAR_CELLS))
                : 0;
              return (
                <li
                  key={row.participantId}
                  className={`set-result__row${
                    row.participantId === participantId ? ' set-result__row--me' : ''
                  }`}
                  style={{ '--c': RANK_COLORS[index % RANK_COLORS.length] } as React.CSSProperties}
                >
                  <span className="set-result__rank">{String(row.rank).padStart(2, '0')}</span>
                  <span className="set-result__name">{nicknameOf(row.participantId)}</span>
                  <span className="set-result__bar" aria-hidden>
                    {Array.from({ length: BAR_CELLS }, (_, cell) => (
                      <i
                        key={cell}
                        className={`set-result__cell${
                          cell < filled ? ' set-result__cell--on' : ''
                        }`}
                      />
                    ))}
                  </span>
                  <span className="set-result__score">+{row.score}점</span>
                </li>
              );
            })}
          </ol>
        </section>

        {/* 아래 컨트롤 덱 — 좌: 코스 누적 순위 / 우: 다음 세트 */}
        <section className="set-result__deck">
          <div className="set-result__totals">
            <h2 className="set-result__deck-title">누적 순위</h2>
            <p className="set-result__deck-sub">세트 점수를 반영한 현재 순위예요.</p>
            <ol className="set-result__cards">
              {courseRanking.map((row, index) => (
                <li
                  key={row.participantId}
                  className={`set-result__card${
                    row.participantId === participantId ? ' set-result__card--me' : ''
                  }`}
                  style={{ '--c': RANK_COLORS[index % RANK_COLORS.length] } as React.CSSProperties}
                >
                  <span className="set-result__card-head">
                    <span className="set-result__card-rank">{row.rank}</span>
                    <span className="set-result__card-name">{nicknameOf(row.participantId)}</span>
                  </span>
                  <span className="set-result__card-score">
                    {row.totalScore}점
                    <i className={`set-result__delta set-result__delta--${row.delta}`}>
                      {DELTA_MARK[row.delta]}
                    </i>
                  </span>
                </li>
              ))}
            </ol>
          </div>

          <div className="set-result__next">
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
              <p className="set-result__next-wait">잠시 후 최종 결과가 나와요</p>
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
              <p className="set-result__next-timer">{secondsLeft}초 뒤 자동으로 시작돼요</p>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
