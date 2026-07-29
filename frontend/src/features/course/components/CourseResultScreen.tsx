import type { CourseScoreEntry } from '../hooks/useCourseProgress';
import './CourseResultScreen.css';

interface CourseResultScreenProps {
  ranking: CourseScoreEntry[];
  totalSessions: number;
  /** participantId → 닉네임. 서버 payload에는 id만 있어 방 스냅샷에서 이름을 붙인다 */
  nicknameById: Map<string, string>;
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  onLeave: () => void;
}

// 코스의 모든 게임이 끝난 뒤 뜨는 종합 결과. 점수는 코스 전체 누적(room:{code}:course:totals)이다.
// 여기서 방은 이미 FINISHED이므로 대기방으로 돌아가지 않는다 — 다시 놀려면 새 방을 만든다.
export function CourseResultScreen({
  ranking,
  totalSessions,
  nicknameById,
  participantId,
  onLeave,
}: CourseResultScreenProps) {
  return (
    <div className="course-result">
      <div className="course-result__card pap-pixel-card">
        <h1 className="course-result__title pap-pixel-title">최종 결과</h1>
        <p className="course-result__subtitle">게임 {totalSessions}개를 모두 마쳤어요!</p>

        <ol className="course-result__list">
          {ranking.map((entry) => (
            <li
              key={entry.participantId}
              className={`course-result__row${
                entry.participantId === participantId ? ' course-result__row--me' : ''
              }${entry.rank === 1 ? ' course-result__row--first' : ''}`}
            >
              <span className="course-result__rank pap-pixel-title">{entry.rank}위</span>
              <span className="course-result__nickname">
                {nicknameById.get(entry.participantId) ?? '알 수 없음'}
                {entry.participantId === participantId && (
                  <span className="course-result__me-badge">ME</span>
                )}
              </span>
              <span className="course-result__score pap-pixel-title">{entry.totalScore}점</span>
            </li>
          ))}
        </ol>

        <button
          type="button"
          className="pap-pixel-btn pap-pixel-btn--primary course-result__leave"
          onClick={onLeave}
        >
          메인으로
        </button>
      </div>
    </div>
  );
}
