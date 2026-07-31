import type { CourseScoreEntry } from '../hooks/useCourseProgress';
import { useAnnouncementSound } from '../../sound/hooks/useAnnouncementSound';
import './CourseResultScreen.css';

interface CourseResultScreenProps {
  ranking: CourseScoreEntry[];
  totalSessions: number;
  /** participantId → 닉네임. 서버 payload에는 id만 있어 방 스냅샷에서 이름을 붙인다 */
  nicknameById: Map<string, string>;
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  /**
   * 대기방 복귀 요청. 방장 전용이 아니라 전원이 각자 누른다 — 화면 전환은 서버의
   * course:member-returned가 내 id로 돌아올 때 이뤄진다(누른 사람만 넘어간다).
   */
  onReturnToLobby: () => void;
  returning: boolean;
  returnError: string | null;
  onLeave: () => void;
}

// 코스의 모든 게임이 끝난 뒤 뜨는 종합 결과. 점수는 코스 전체 누적(room:{code}:course:totals)이다.
// "방으로 돌아가기"는 전원이 각자 누르며, 누른 사람만 대기방으로 넘어간다 — 한 번에 전원이
// 들어오지 않으므로 결과를 더 보고 싶은 사람은 남아 있을 수 있다. 아직 안 돌아온 사람도 방을
// 떠난 게 아니라서 대기방 타일에 "게임 중"으로 자리가 남는다(방장 자격·입장 순서 유지).
// 방을 아예 떠나려면 "메인으로".
export function CourseResultScreen({
  ranking,
  totalSessions,
  nicknameById,
  participantId,
  onReturnToLobby,
  returning,
  returnError,
  onLeave,
}: CourseResultScreenProps) {
  useAnnouncementSound(
    ranking.length > 0,
    `course-final:${totalSessions}:${ranking[0]?.participantId ?? 'unknown'}`,
    '/assets/sounds/final-winner.mp3',
  );

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
          onClick={onReturnToLobby}
          disabled={returning}
        >
          {returning ? '돌아가는 중...' : '방으로 돌아가기'}
        </button>
        <p className="course-result__wait-host">
          먼저 돌아가도 괜찮아요. 아직 결과를 보는 사람은 대기방에 "게임 중"으로 남아 있어요
        </p>
        {returnError && <p className="course-result__error">{returnError}</p>}

        <button
          type="button"
          className="pap-pixel-btn course-result__leave"
          onClick={onLeave}
        >
          메인으로
        </button>
      </div>
    </div>
  );
}
