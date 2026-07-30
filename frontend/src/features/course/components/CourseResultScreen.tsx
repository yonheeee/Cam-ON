import type { CourseScoreEntry } from '../hooks/useCourseProgress';
import './CourseResultScreen.css';

interface CourseResultScreenProps {
  ranking: CourseScoreEntry[];
  totalSessions: number;
  /** participantId → 닉네임. 서버 payload에는 id만 있어 방 스냅샷에서 이름을 붙인다 */
  nicknameById: Map<string, string>;
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  /** 방장인가 — 대기방 복귀 버튼은 방장만 누를 수 있다(서버도 검증) */
  isHost: boolean;
  /** [방장 전용] 대기방 복귀 요청. 화면 전환은 서버의 course:reset 브로드캐스트가 담당 */
  onReturnToLobby: () => void;
  returning: boolean;
  returnError: string | null;
  onLeave: () => void;
}

// 코스의 모든 게임이 끝난 뒤 뜨는 종합 결과. 점수는 코스 전체 누적(room:{code}:course:totals)이다.
// 방장이 "방으로 돌아가기"를 누르면 서버가 점수를 초기화하고 방을 WAITING으로 되돌려
// 전원이 함께 대기방으로 돌아간다(course:reset). 개별로 떠나려면 "메인으로".
export function CourseResultScreen({
  ranking,
  totalSessions,
  nicknameById,
  participantId,
  isHost,
  onReturnToLobby,
  returning,
  returnError,
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

        {isHost ? (
          <button
            type="button"
            className="pap-pixel-btn pap-pixel-btn--primary course-result__leave"
            onClick={onReturnToLobby}
            disabled={returning}
          >
            {returning ? '돌아가는 중...' : '방으로 돌아가기'}
          </button>
        ) : (
          <p className="course-result__wait-host">
            방장이 [방으로 돌아가기]를 누르면 함께 대기방으로 이동해요
          </p>
        )}
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
