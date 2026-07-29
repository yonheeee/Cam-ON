import { useMemo, useState } from 'react';
import {
  GAME_LABELS,
  ROUND_UNIT_HINTS,
  maxRoundsFor,
  minRoundsFor,
  type CatalogGame,
  type CatalogTopic,
  type Course,
  type CourseItemInput,
} from '../api/courseApi';
import './CourseEditorModal.css';

const MAX_COURSE_LENGTH = 7; // 백엔드 CourseService.MAX_COURSE_LENGTH와 같은 값

interface CourseEditorModalProps {
  games: CatalogGame[];
  course: Course | null;
  /** 주제를 고르는 게임의 주제 목록 (useTopics가 gameId별로 모아 준다) */
  topicsByGameId: Record<number, CatalogTopic[]>;
  /** 지금 방에 있는 사람 수 — 인원 조건/최소 라운드(물건 가져오기) 안내에 쓴다 */
  playerCount: number;
  saving: boolean;
  saveError: string | null;
  onSave: (items: CourseItemInput[]) => Promise<boolean>;
  onClose: () => void;
}

// 편집 중인 코스 한 칸. 서버 저장 형식(CourseItemInput)과 같지만 편집 중에는 topicId가
// 아직 안 정해질 수 있어 따로 둔다.
interface DraftItem {
  gameId: number;
  roundCount: number;
  topicId: number | null;
}

// 방장이 게임 순서/라운드 수/주제를 정하는 팝업. 저장은 전체 교체(PUT)라 여기서 만든 배열이
// 곧 진행 순서가 된다.
export function CourseEditorModal({
  games,
  course,
  topicsByGameId,
  playerCount,
  saving,
  saveError,
  onSave,
  onClose,
}: CourseEditorModalProps) {
  const [draft, setDraft] = useState<DraftItem[]>(
    () =>
      course?.items.map((item) => ({
        gameId: item.gameId,
        roundCount: item.roundCount,
        topicId: item.topicId,
      })) ?? [],
  );
  const [localError, setLocalError] = useState<string | null>(null);

  const gamesById = useMemo(
    () => new Map(games.map((game) => [game.gameId, game])),
    [games],
  );
  // 담을 수 있는 게임만 추가 버튼으로 노출한다(준비 중인 게임은 아래에 따로 안내).
  const selectableGames = games.filter((game) => game.supported);
  const unsupportedGames = games.filter((game) => !game.supported);

  const addItem = (game: CatalogGame) => {
    if (draft.length >= MAX_COURSE_LENGTH) {
      setLocalError(`코스에는 게임을 최대 ${MAX_COURSE_LENGTH}개까지 담을 수 있어요.`);
      return;
    }
    setLocalError(null);
    setDraft((prev) => [
      ...prev,
      {
        gameId: game.gameId,
        roundCount: minRoundsFor(game, playerCount),
        topicId: null,
      },
    ]);
  };

  const removeItem = (index: number) => {
    setLocalError(null);
    setDraft((prev) => prev.filter((_, i) => i !== index));
  };

  const moveItem = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= draft.length) return;
    setDraft((prev) => {
      const next = [...prev];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const changeRounds = (index: number, delta: number) => {
    setDraft((prev) =>
      prev.map((item, i) => {
        if (i !== index) return item;
        const game = gamesById.get(item.gameId);
        if (!game) return item;
        const min = minRoundsFor(game, playerCount);
        const max = maxRoundsFor(game);
        const next = Math.min(max, Math.max(min, item.roundCount + delta));
        return { ...item, roundCount: next };
      }),
    );
  };

  const changeTopic = (index: number, topicId: number | null) => {
    setDraft((prev) => prev.map((item, i) => (i === index ? { ...item, topicId } : item)));
  };

  // 저장 전에 프론트에서 잡아낼 수 있는 문제를 먼저 보여준다(서버도 같은 것을 검증한다).
  const validationMessage = useMemo(() => {
    if (draft.length === 0) return '게임을 최소 1개 담아야 해요.';
    for (const [index, item] of draft.entries()) {
      const game = gamesById.get(item.gameId);
      if (!game) return `${index + 1}번째 칸의 게임을 찾을 수 없어요.`;
      if (game.requiresTopic && item.topicId === null) {
        return `${index + 1}번째 ${GAME_LABELS[game.name]}의 주제를 골라 주세요.`;
      }
      const topics = topicsByGameId[game.gameId];
      const topic = topics?.find((candidate) => candidate.topicId === item.topicId);
      // 제시어가 (인원 x 라운드)보다 적으면 게임이 중간에 끊긴다 — 서버도 거부한다.
      if (topic && topic.missionCount < playerCount * item.roundCount) {
        return `'${topic.name}' 주제의 제시어(${topic.missionCount}개)로는 ${playerCount}명 x ${item.roundCount}라운드를 채울 수 없어요.`;
      }
    }
    return null;
  }, [draft, gamesById, topicsByGameId, playerCount]);

  const handleSave = async () => {
    if (validationMessage) {
      setLocalError(validationMessage);
      return;
    }
    const ok = await onSave(
      draft.map((item) => ({
        gameId: item.gameId,
        roundCount: item.roundCount,
        topicId: item.topicId,
      })),
    );
    if (ok) onClose();
  };

  return (
    <div className="pap-modal-backdrop">
      <div className="pap-modal course-editor">
        <div className="course-editor__card pap-pixel-card">
          <h2 className="course-editor__title pap-pixel-title">게임 구성</h2>
          <p className="course-editor__hint">
            위에서 아래 순서로 진행돼요. 지금 인원은 {playerCount}명이에요.
          </p>

          <ol className="course-editor__list">
            {draft.map((item, index) => {
              const game = gamesById.get(item.gameId);
              if (!game) return null;
              const min = minRoundsFor(game, playerCount);
              const max = maxRoundsFor(game);
              const topics = topicsByGameId[game.gameId] ?? [];
              const playersOk =
                playerCount >= game.minPlayers && playerCount <= game.maxPlayers;
              return (
                <li key={index} className="course-editor__row">
                  <span className="course-editor__seq pap-pixel-title">
                    {String(index + 1).padStart(2, '0')}
                  </span>
                  <span className="course-editor__game">
                    <strong>{GAME_LABELS[game.name]}</strong>
                    <small>{ROUND_UNIT_HINTS[game.name]}</small>
                    {!playersOk && (
                      <small className="course-editor__warn">
                        {game.minPlayers}~{game.maxPlayers}명이어야 진행돼요 (지금 {playerCount}명)
                      </small>
                    )}
                  </span>

                  {game.requiresTopic && (
                    <select
                      className="course-editor__topic"
                      value={item.topicId ?? ''}
                      onChange={(e) =>
                        changeTopic(index, e.target.value === '' ? null : Number(e.target.value))
                      }
                      aria-label="주제 선택"
                    >
                      <option value="">주제 선택</option>
                      {topics.map((topic) => (
                        <option key={topic.topicId} value={topic.topicId}>
                          {topic.name} ({topic.missionCount}개)
                        </option>
                      ))}
                    </select>
                  )}

                  <span className="course-editor__rounds">
                    <button
                      type="button"
                      className="pap-pixel-btn lobby-btn-sm"
                      onClick={() => changeRounds(index, -1)}
                      disabled={item.roundCount <= min}
                      aria-label="라운드 줄이기"
                    >
                      −
                    </button>
                    <span className="course-editor__round-count pap-pixel-title">
                      {item.roundCount}R
                    </span>
                    <button
                      type="button"
                      className="pap-pixel-btn lobby-btn-sm"
                      onClick={() => changeRounds(index, 1)}
                      disabled={item.roundCount >= max}
                      aria-label="라운드 늘리기"
                    >
                      +
                    </button>
                  </span>

                  <span className="course-editor__row-actions">
                    <button
                      type="button"
                      className="pap-pixel-btn lobby-btn-sm"
                      onClick={() => moveItem(index, -1)}
                      disabled={index === 0}
                      aria-label="위로"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      className="pap-pixel-btn lobby-btn-sm"
                      onClick={() => moveItem(index, 1)}
                      disabled={index === draft.length - 1}
                      aria-label="아래로"
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      className="pap-pixel-btn pap-pixel-btn--coral lobby-btn-sm"
                      onClick={() => removeItem(index)}
                      aria-label="빼기"
                    >
                      ✕
                    </button>
                  </span>
                </li>
              );
            })}
            {draft.length === 0 && (
              <li className="course-editor__empty">아래에서 게임을 추가해 주세요.</li>
            )}
          </ol>

          <div className="course-editor__add">
            {selectableGames.map((game) => (
              <button
                key={game.gameId}
                type="button"
                className="pap-pixel-btn"
                onClick={() => addItem(game)}
                disabled={draft.length >= MAX_COURSE_LENGTH}
              >
                + {GAME_LABELS[game.name]}
              </button>
            ))}
          </div>
          {unsupportedGames.length > 0 && (
            <p className="course-editor__unsupported">
              준비 중: {unsupportedGames.map((game) => GAME_LABELS[game.name]).join(', ')}
            </p>
          )}

          {(localError ?? saveError ?? validationMessage) && (
            <p className="course-editor__error">{localError ?? saveError ?? validationMessage}</p>
          )}

          <div className="course-editor__actions">
            <button type="button" className="pap-pixel-btn" onClick={onClose} disabled={saving}>
              취소
            </button>
            <button
              type="button"
              className="pap-pixel-btn pap-pixel-btn--primary"
              onClick={handleSave}
              disabled={saving || !!validationMessage}
            >
              {saving ? '저장 중...' : '저장'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
