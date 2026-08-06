import { useMemo, useState } from 'react';
import { GAME_LABELS } from '../api/courseApi';
import { SetResultScreen, type CourseRankRow, type SetResultRow } from './SetResultScreen';
import './SetResultPreview.css';

// [개발 전용] /dev/set-result — 방을 만들지 않고 중간 결과 화면과 막대 애니메이션을 확인한다.
// 점수를 직접 바꾸거나 동점 프리셋을 눌러 QA 상황을 빠르게 재현할 수 있다.

const PARTICIPANTS = [
  { participantId: 'p1', nickname: '서희' },
  { participantId: 'p2', nickname: '도도' },
  { participantId: 'p3', nickname: '하코' },
  { participantId: 'p4', nickname: '민수' },
] as const;

const PRESETS = {
  tie: { label: '3인 동점', scores: [4, 4, 4, null] },
  normal: { label: '일반 순위', scores: [5, 3, 2, 1] },
  close: { label: '근소한 차이', scores: [100, 99, 98, 97] },
  zero: { label: '0점 포함', scores: [5, 3, 0, 0] },
} as const;

type PreviewScore = number | null;
type PreviewDelta = CourseRankRow['delta'];

function rankScores(scores: PreviewScore[]): SetResultRow[] {
  const active = scores
    .map((score, index) => ({ ...PARTICIPANTS[index], score, originalIndex: index }))
    .filter((entry): entry is typeof entry & { score: number } => entry.score !== null)
    .sort((a, b) => b.score - a.score || a.originalIndex - b.originalIndex);

  let previousScore = Number.NaN;
  let previousRank = 0;
  return active.map((entry, index) => {
    const rank = entry.score === previousScore ? previousRank : index + 1;
    previousScore = entry.score;
    previousRank = rank;
    return { participantId: entry.participantId, score: entry.score, rank };
  });
}

export function SetResultPreview() {
  const [scores, setScores] = useState<PreviewScore[]>([4, 4, 4, null]);
  const [replayKey, setReplayKey] = useState(0);
  const [panelOpen, setPanelOpen] = useState(true);
  const [isFinalSet, setIsFinalSet] = useState(false);
  const [deltas, setDeltas] = useState<PreviewDelta[]>(['up', 'same', 'down', 'same']);

  const setResult = useMemo(() => rankScores(scores), [scores]);
  const courseRanking = useMemo<CourseRankRow[]>(
    () =>
      setResult.map((row) => ({
        participantId: row.participantId,
        totalScore: row.score + 1,
        rank: row.rank,
        delta: deltas[PARTICIPANTS.findIndex((entry) => entry.participantId === row.participantId)],
      })),
    [deltas, setResult],
  );

  const applyPreset = (preset: keyof typeof PRESETS) => {
    setScores([...PRESETS[preset].scores]);
    setReplayKey((current) => current + 1);
  };

  const updateScore = (index: number, value: string) => {
    setScores((current) => {
      const next = [...current];
      next[index] = value === '' ? null : Math.max(0, Number(value));
      return next;
    });
  };

  return (
    <div className="set-result-preview">
      <SetResultScreen
        key={replayKey}
        setIndex={isFinalSet ? 3 : 1}
        totalSets={3}
        participantId="p2"
        gameName="FETCH_OBJECT"
        setResult={setResult}
        courseRanking={courseRanking}
        nicknameById={new Map(PARTICIPANTS.map((entry) => [entry.participantId, entry.nickname]))}
        nextGameLabel={isFinalSet ? null : GAME_LABELS.NINJA}
        isHost
        secondsLeft={isFinalSet ? null : 7}
          onNext={() => {}}
          participantColorIndexById={new Map(
            PARTICIPANTS.map((entry, index) => [entry.participantId, index + 1]),
          )}
      />

      <aside className={`set-result-preview__panel${panelOpen ? '' : ' is-collapsed'}`}>
        <button
          type="button"
          className="set-result-preview__toggle"
          onClick={() => setPanelOpen((open) => !open)}
        >
          {panelOpen ? 'QA 패널 접기' : 'QA 패널 열기'}
        </button>

        {panelOpen && (
          <div className="set-result-preview__controls">
            <strong>중간 결과 테스트</strong>
            <div className="set-result-preview__presets">
              {Object.entries(PRESETS).map(([key, preset]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => applyPreset(key as keyof typeof PRESETS)}
                >
                  {preset.label}
                </button>
              ))}
            </div>

            <div className="set-result-preview__modes">
              <button
                type="button"
                className={!isFinalSet ? 'is-active' : ''}
                onClick={() => setIsFinalSet(false)}
              >
                다음 세트 있음
              </button>
              <button
                type="button"
                className={isFinalSet ? 'is-active' : ''}
                onClick={() => setIsFinalSet(true)}
              >
                마지막 세트
              </button>
            </div>

            <div className="set-result-preview__scores">
              {PARTICIPANTS.map((participant, index) => (
                <label key={participant.participantId}>
                  <span>{participant.nickname}</span>
                  <input
                    type="number"
                    min="0"
                    value={scores[index] ?? ''}
                    placeholder="제외"
                    onChange={(event) => updateScore(index, event.target.value)}
                  />
                  <select
                    aria-label={`${participant.nickname} 순위 변동`}
                    value={deltas[index]}
                    onChange={(event) =>
                      setDeltas((current) => {
                        const next = [...current];
                        next[index] = event.target.value as PreviewDelta;
                        return next;
                      })
                    }
                  >
                    <option value="up">상승 ▲</option>
                    <option value="same">유지 —</option>
                    <option value="down">하락 ▼</option>
                  </select>
                </label>
              ))}
            </div>

            <button
              type="button"
              className="set-result-preview__replay"
              onClick={() => setReplayKey((current) => current + 1)}
            >
              애니메이션 다시 재생
            </button>
            <small>빈 입력값은 해당 참가자를 화면에서 제외합니다.</small>
          </div>
        )}
      </aside>
    </div>
  );
}
