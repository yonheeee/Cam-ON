import { ParticipantTile, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useMemo, useState } from 'react';
import type { CourseScoreEntry } from '../hooks/useCourseProgress';
import { useAnnouncementSound } from '../../sound/hooks/useAnnouncementSound';
import { SpeakingIndicator } from '../../webrtc/components/SpeakingIndicator';
import { useSpeakingIdentities } from '../../webrtc/hooks/useSpeakingIdentities';
import { playerColor } from '../../room/lib/playerColor';
import './CourseResultScreen.css';

interface CourseResultScreenProps {
  ranking: CourseScoreEntry[];
  totalSessions: number;
  /** participantId → 닉네임. 서버 payload에는 id만 있어 방 스냅샷에서 이름을 붙인다 */
  nicknameById: Map<string, string>;
  joinOrder: string[];
  /** 내 participantId — 내 줄을 강조하려고 */
  participantId: string;
  /**
   * 이미 대기방으로 돌아간 사람들. 복귀가 개별 행동이라 결과 화면과 대기방이 동시에 떠 있는데,
   * 캠까지 양쪽에 다 보이면 "저 사람은 어디 있는 건가"가 헷갈린다 — 돌아간 사람의 캠은 여기서
   * 내리고 순위표에만 남긴다(점수는 코스 기록이라 사라지면 안 된다).
   */
  returnedParticipantIds: Set<string>;
  /**
   * 대기방 복귀 요청. 방장 전용이 아니라 전원이 각자 누른다 — 화면 전환은 서버의
   * course:member-returned가 내 id로 돌아올 때 이뤄진다(누른 사람만 넘어간다).
   */
  onReturnToLobby: () => void;
  returning: boolean;
  returnError: string | null;
  onLeave: () => void;
}

const FIREWORK_COLORS = ['#ff6f61', '#ffd84d', '#52d6cc', '#a98bff', '#fff3c4'];
const FIREWORK_BURSTS = [
  { x: '21%', y: '16%', delay: '0.15s' },
  { x: '50%', y: '10%', delay: '0.75s' },
  { x: '79%', y: '16%', delay: '1.35s' },
];
const FIREWORK_PARTICLES = 14;
const RANK_ROW_STAGGER_MS = 160;
const RANK_SCORE_DURATION_MS = 900;
const CO_WINNER_ROTATION_MS = 3000;

const TrophyIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M8 4h8v4c0 3-1.8 5-4 5S8 11 8 8V4Z" fill="currentColor" />
    <path d="M8 6H5v1c0 2.2 1.3 3.5 3.3 3.5M16 6h3v1c0 2.2-1.3 3.5-3.3 3.5" />
    <path d="M12 13v4M8.5 20h7M10 17h4" />
  </svg>
);

const WinnerCrownIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M3 8v9.5h18V8l-4.5 3L12 5.5 7.5 11 3 8Z" fill="currentColor" />
  </svg>
);

// 코스의 모든 게임이 끝난 뒤 뜨는 종합 결과. 점수는 코스 전체 누적(room:{code}:course:totals)이다.
// 배경 그림(시상대 무대)의 칸에 맞춰: 가운데 큰 화면 = 우승자 캠, 아래 3칸 = 2~4위 캠,
// 오른쪽 = 최종 순위표와 버튼.
//
// "대기방으로"는 전원이 각자 누르며, 누른 사람만 대기방으로 넘어간다 — 한 번에 전원이
// 들어오지 않으므로 결과를 더 보고 싶은 사람은 남아 있을 수 있다. 아직 안 돌아온 사람도 방을
// 떠난 게 아니라서 대기방 타일에 "게임 중"으로 자리가 남는다(방장 자격·입장 순서 유지).
// 방을 아예 떠나려면 "방 나가기".
export function CourseResultScreen({
  ranking,
  totalSessions,
  nicknameById,
  joinOrder,
  returnedParticipantIds,
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

  const speakingIds = useSpeakingIdentities();

  const nicknameOf = (id: string) => nicknameById.get(id) ?? '알 수 없음';
  const fallbackOrder = useMemo(
    () => ranking.map((entry) => entry.participantId).sort(),
    [ranking],
  );
  const colorOf = (id: string) => playerColor(id, joinOrder, fallbackOrder);

  // 캠이 아직 안 붙었거나 카메라를 끈 참가자는 닉네임 첫 글자를 아바타로 보여준다.
  // 먼저 대기방으로 간 사람도 마찬가지다 — LiveKit 트랙은 방을 떠나기 전까진 계속 살아 있어서
  // 그냥 두면 대기방에 있는 사람의 캠이 결과 화면에도 겹쳐 보인다. 자리(순위)는 그대로 두고
  // 캠만 아바타로 바꾸고 "대기방으로 갔어요"를 덧붙인다.
  // 닉네임은 닌자 화면과 같은 방식(캠 위 픽셀 스티커, 배경 = 그 사람의 고유색)으로 얹는다.
  const renderCam = (id: string) => {
    const returned = returnedParticipantIds.has(id);
    const trackRef = returned ? undefined : trackByIdentity.get(id);
    return (
      <>
        {trackRef ? (
          <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
        ) : (
          <span className="course-result__avatar pap-pixel-title">{nicknameOf(id).slice(0, 1)}</span>
        )}
        {returned && <span className="course-result__left">대기방으로 갔어요</span>}
        {/* 먼저 대기방으로 간 사람은 캠과 함께 표시도 내린다(위 trackRef와 같은 이유) */}
        <SpeakingIndicator active={!returned && speakingIds.has(id)} color={colorOf(id)} />
        {/* 하단 그라데이션 바 + 참가자 고유색 띠 + 흰 이름 (::before가 색 띠) */}
        <span className="course-result__cam-name">
          <span>{nicknameOf(id)}</span>
        </span>
      </>
    );
  };

  const coWinners = ranking.filter((entry) => entry.rank === 1);
  const winnerSignature = coWinners.map((entry) => entry.participantId).join('|');
  const [activeWinnerIndex, setActiveWinnerIndex] = useState(0);
  const winner = coWinners[activeWinnerIndex] ?? ranking[0] ?? null;
  const runnersUp = ranking.filter((entry) => entry.rank > 1).slice(0, 3);
  const rankingSignature = ranking
    .map((entry) => `${entry.participantId}:${entry.rank}:${entry.totalScore}`)
    .join('|');
  const rankRevealDuration =
    Math.max(0, ranking.length - 1) * RANK_ROW_STAGGER_MS + RANK_SCORE_DURATION_MS;
  const [rankRevealElapsed, setRankRevealElapsed] = useState(0);

  useEffect(() => {
    setActiveWinnerIndex(0);
    if (coWinners.length <= 1) return;

    const intervalId = window.setInterval(() => {
      setActiveWinnerIndex((current) => (current + 1) % coWinners.length);
    }, CO_WINNER_ROTATION_MS);
    return () => window.clearInterval(intervalId);
  }, [coWinners.length, winnerSignature]);

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setRankRevealElapsed(rankRevealDuration);
      return;
    }

    let frameId = 0;
    const startedAt = performance.now();
    const tick = (now: number) => {
      const elapsed = Math.min(rankRevealDuration, now - startedAt);
      setRankRevealElapsed(elapsed);
      if (elapsed < rankRevealDuration) frameId = requestAnimationFrame(tick);
    };

    setRankRevealElapsed(0);
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [rankRevealDuration, rankingSignature]);

  return (
    <div className="course-result">
      <div className="course-result__stage">
        <div className="course-result__fireworks" aria-hidden>
          {FIREWORK_BURSTS.map((burst, burstIndex) => (
            <span
              key={burstIndex}
              className="course-result__firework"
              style={
                {
                  '--firework-x': burst.x,
                  '--firework-y': burst.y,
                  '--firework-delay': burst.delay,
                } as React.CSSProperties
              }
            >
              {Array.from({ length: FIREWORK_PARTICLES }, (_, particleIndex) => (
                <i
                  key={particleIndex}
                  className="course-result__firework-particle"
                  style={
                    {
                      '--firework-angle': `${(360 / FIREWORK_PARTICLES) * particleIndex}deg`,
                      '--firework-distance': `calc(var(--u) * ${
                        9 + (particleIndex % 3) * 1.5
                      })`,
                      '--firework-color':
                        FIREWORK_COLORS[(burstIndex + particleIndex) % FIREWORK_COLORS.length],
                    } as React.CSSProperties
                  }
                />
              ))}
            </span>
          ))}
        </div>
        <p className="course-result__plate">
          {coWinners.length > 1
            ? `공동 우승자! ${activeWinnerIndex + 1}/${coWinners.length}`
            : '오늘의 우승자!'}
        </p>

        {/* 가운데 큰 화면 — 우승자 캠 */}
        <div
          key={winner?.participantId ?? 'no-winner'}
          className="course-result__winner"
          style={
            {
              '--c': winner ? colorOf(winner.participantId) : 'var(--pap-player-1)',
            } as React.CSSProperties
          }
        >
          {winner ? renderCam(winner.participantId) : null}
        </div>

        {/* 아래 3칸 — 2~4위 캠. 인원이 적으면 빈 칸을 만들지 않고 있는 만큼만 그린다 */}
        <div className="course-result__podium">
          {runnersUp.map((entry) => (
            <div
              key={entry.participantId}
              className="course-result__podium-tile"
              style={{ '--c': colorOf(entry.participantId) } as React.CSSProperties}
            >
              {renderCam(entry.participantId)}
            </div>
          ))}
        </div>

        {/* 오른쪽 위 — 최종 순위표 */}
        <section className="course-result__ranking">
          <h1 className="course-result__ranking-title">
            {TrophyIcon}
            최종 순위
          </h1>
          <ol className="course-result__list">
            {ranking.map((entry, index) => {
              const rowDelay = index * RANK_ROW_STAGGER_MS;
              const progress = Math.min(
                1,
                Math.max(0, (rankRevealElapsed - rowDelay) / RANK_SCORE_DURATION_MS),
              );
              const displayedScore = Math.round(entry.totalScore * progress);
              return (
                <li
                  key={entry.participantId}
                  className={`course-result__row${
                    entry.rank === 1 ? ' course-result__row--first' : ''
                  }${
                    entry.participantId === winner?.participantId && coWinners.length > 1
                      ? ' course-result__row--active-winner'
                      : ''
                  }${progress === 1 ? ' course-result__row--settled' : ''}`}
                  style={
                    {
                      '--c': colorOf(entry.participantId),
                      '--row-index': index,
                    } as React.CSSProperties
                  }
                >
                  <span className="course-result__rank-badge">
                    {entry.rank === 1 ? WinnerCrownIcon : entry.rank}
                  </span>
                  <span className="course-result__name-wrap">
                    <span className="course-result__nickname">{nicknameOf(entry.participantId)}</span>
                  </span>
                  <span className="course-result__score">{displayedScore}점</span>
                </li>
              );
            })}
          </ol>
        </section>

        {/* 오른쪽 아래 — 버튼. 대기방 복귀는 전원이 각자 누르고 누른 사람만 넘어간다
            (예전엔 방장 전용이었다). 방을 아예 떠나려면 "방 나가기". */}
        <div className="course-result__actions">
          {/* 안내는 버튼 아래 문구가 아니라 hover 말풍선으로 — 문구가 끼면 아래 버튼이 밀린다 */}
          <span
            className="course-result__btn-wrap"
            data-hint={'먼저 가도 괜찮아요. 남은 사람은 대기방에 "게임 중"으로 표시돼요'}
          >
            <button
              type="button"
              className="pap-pixel-btn course-result__btn course-result__btn--lobby"
              onClick={onReturnToLobby}
              disabled={returning}
            >
              {returning ? '돌아가는 중...' : '대기방으로'} {!returning && <span aria-hidden>→</span>}
            </button>
          </span>
          <button type="button" className="pap-pixel-btn course-result__btn" onClick={onLeave}>
            방 나가기
          </button>
          {returnError && <p className="course-result__error">{returnError}</p>}
        </div>
      </div>
    </div>
  );
}
