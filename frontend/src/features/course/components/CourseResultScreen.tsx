import { ParticipantTile, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useMemo } from 'react';
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
  /** 방장인가 — 대기방 복귀 버튼은 방장만 누를 수 있다(서버도 검증) */
  isHost: boolean;
  /** [방장 전용] 대기방 복귀 요청. 화면 전환은 서버의 course:reset 브로드캐스트가 담당 */
  onReturnToLobby: () => void;
  returning: boolean;
  returnError: string | null;
  onLeave: () => void;
}

// 2~4위 단상 타일 색(1위는 가운데 큰 화면이라 색을 안 쓴다). 중간 결과(SetResultScreen)의
// 등수 색 배열과 같은 순서 — 두 화면에서 같은 사람이 같은 색으로 보인다.
const PODIUM_COLORS = [
  'var(--pap-play-yellow)',
  'var(--pap-arcade-teal)',
  'var(--pap-lavender)',
];

// 코스의 모든 게임이 끝난 뒤 뜨는 종합 결과. 점수는 코스 전체 누적(room:{code}:course:totals)이다.
// 배경 그림(시상대 무대)의 칸에 맞춰: 가운데 큰 화면 = 우승자 캠, 아래 3칸 = 2~4위 캠,
// 오른쪽 = 최종 순위표와 버튼.
//
// 방장이 "대기방으로"를 누르면 서버가 점수를 초기화하고 방을 WAITING으로 되돌려
// 전원이 함께 대기방으로 돌아간다(course:reset). 개별로 떠나려면 "방 나가기".
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
  useAnnouncementSound(
    ranking.length > 0,
    `course-final:${totalSessions}:${ranking[0]?.participantId ?? 'unknown'}`,
    '/assets/sounds/final-winner.mp3',
  );

  // 캠 타일은 닌자/몸으로말해요 화면과 같은 방식으로 붙인다(LiveKit identity = participantId).
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });
  const trackByIdentity = useMemo(
    () => new Map(tracks.map((t) => [t.participant.identity, t])),
    [tracks],
  );

  const nicknameOf = (id: string) => nicknameById.get(id) ?? '알 수 없음';

  // 캠이 아직 안 붙었거나 카메라를 끈 참가자는 닉네임 첫 글자를 아바타로 보여준다.
  const renderCam = (id: string) => {
    const trackRef = trackByIdentity.get(id);
    return trackRef ? (
      <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
    ) : (
      <span className="course-result__avatar pap-pixel-title">{nicknameOf(id).slice(0, 1)}</span>
    );
  };

  const winner = ranking[0] ?? null;
  const runnersUp = ranking.slice(1, 4);

  return (
    <div className="course-result">
      <div className="course-result__stage">
        <p className="course-result__plate">오늘의 우승자!</p>

        {/* 가운데 큰 화면 — 우승자 캠 */}
        <div className="course-result__winner">
          {winner ? renderCam(winner.participantId) : null}
        </div>

        {/* 아래 3칸 — 2~4위 캠. 인원이 적으면 빈 칸을 만들지 않고 있는 만큼만 그린다 */}
        <div className="course-result__podium">
          {runnersUp.map((entry, index) => (
            <div
              key={entry.participantId}
              className="course-result__podium-tile"
              style={{ '--c': PODIUM_COLORS[index] } as React.CSSProperties}
            >
              {renderCam(entry.participantId)}
            </div>
          ))}
        </div>

        {/* 오른쪽 위 — 최종 순위표 */}
        <section className="course-result__ranking">
          <h1 className="course-result__ranking-title">최종 순위</h1>
          <ol className="course-result__list">
            {ranking.map((entry) => (
              <li
                key={entry.participantId}
                className={`course-result__row${
                  entry.rank === 1 ? ' course-result__row--first' : ''
                }${entry.participantId === participantId ? ' course-result__row--me' : ''}`}
              >
                <span className="course-result__rank">{entry.rank}</span>
                <span className="course-result__nickname">{nicknameOf(entry.participantId)}</span>
                <span className="course-result__score">{entry.totalScore}점</span>
              </li>
            ))}
          </ol>
        </section>

        {/* 오른쪽 아래 — 버튼. 대기방 복귀는 방장만, 나가기는 누구나 */}
        <div className="course-result__actions">
          {isHost ? (
            <button
              type="button"
              className="pap-pixel-btn course-result__btn course-result__btn--lobby"
              onClick={onReturnToLobby}
              disabled={returning}
            >
              {returning ? '돌아가는 중...' : '대기방으로'} {!returning && <span aria-hidden>→</span>}
            </button>
          ) : (
            <p className="course-result__wait-host">방장이 누르면 함께 대기방으로 이동해요</p>
          )}
          <button type="button" className="pap-pixel-btn course-result__btn" onClick={onLeave}>
            방 나가기
          </button>
          {returnError && <p className="course-result__error">{returnError}</p>}
        </div>
      </div>
    </div>
  );
}
