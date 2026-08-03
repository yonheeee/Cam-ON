import { useMemo, useState } from 'react';
import { useAiHealth } from '../../fetch/hooks/useAiHealth';
import {
  GAME_LABELS,
  SET_UNIT_HINTS,
  type CatalogGame,
  type CatalogTopic,
  type Course,
  type CourseItemInput,
} from '../api/courseApi';
import './CourseEditorModal.css';

const MAX_COURSE_LENGTH = 7; // 백엔드 CourseService.MAX_COURSE_LENGTH와 같은 값 — 최대 7세트

// AI 서버가 꺼져 있을 때 물건 가져오기를 막는 이유를 사람이 읽을 수 있게. 담기 시도와 저장
// 검증 두 곳에서 같은 문구를 쓴다.
const AI_DOWN_MESSAGE = '인식 서버에 연결할 수 없어 지금은 담을 수 없어요.';

interface CourseEditorModalProps {
  games: CatalogGame[];
  course: Course | null;
  /** 주제를 고르는 게임의 주제 목록 (useTopics가 gameId별로 모아 준다) */
  topicsByGameId: Record<number, CatalogTopic[]>;
  /** 지금 방에 있는 사람 수 — 인원 조건 안내에 쓴다 */
  playerCount: number;
  saving: boolean;
  saveError: string | null;
  onSave: (items: CourseItemInput[]) => Promise<boolean>;
  onClose: () => void;
}

// 편집 중인 코스 한 칸 = 게임 1세트. 서버 저장 형식(CourseItemInput)과 같지만 편집 중에는
// topicId가 아직 안 정해질 수 있어 따로 둔다. 라운드 수는 게임별 고정이라 여기 없다.
interface DraftItem {
  gameId: number;
  topicId: number | null;
}

// 방장이 세트 큐(게임 순서)와 주제를 정하는 팝업. 같은 게임을 여러 번 담으면 그만큼 여러
// 세트를 하는 것이다 (예: 닌자 2세트 = [닌자, 닌자]). 저장은 전체 교체(PUT)라 여기서 만든
// 배열이 곧 진행 순서가 된다.
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

  // 물건 가져오기는 AI 서버(별개 호스트)의 인식에 의존한다. 그 서버가 꺼져 있으면 게임은
  // 시작되지만 인식만 조용히 실패해서 "게임이 고장났다"로 보인다 — 코스에 담는 시점에 막는다.
  // 모달이 열려 있을 때만 확인한다.
  const aiHealth = useAiHealth(true);
  const fetchBlocked = aiHealth === 'down';
  const isFetchGame = (game: CatalogGame) => game.name === 'FETCH_OBJECT';

  const addItem = (game: CatalogGame) => {
    if (draft.length >= MAX_COURSE_LENGTH) {
      setLocalError(`코스에는 게임을 다 합쳐 최대 ${MAX_COURSE_LENGTH}세트까지 담을 수 있어요.`);
      return;
    }
    if (isFetchGame(game) && fetchBlocked) {
      setLocalError(AI_DOWN_MESSAGE);
      return;
    }
    setLocalError(null);
    setDraft((prev) => [
      ...prev,
      {
        gameId: game.gameId,
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

  const changeTopic = (index: number, topicId: number | null) => {
    setDraft((prev) => prev.map((item, i) => (i === index ? { ...item, topicId } : item)));
  };

  // 저장 전에 프론트에서 잡아낼 수 있는 문제를 먼저 보여준다(서버도 같은 것을 검증한다 —
  // 단 AI 서버 생존 여부는 Spring이 알 수 없으므로 이 검증만 프론트 단독이다).
  const validationMessage = useMemo(() => {
    if (draft.length === 0) return '게임을 최소 1세트 담아야 해요.';
    for (const [index, item] of draft.entries()) {
      const game = gamesById.get(item.gameId);
      if (!game) return `${index + 1}번째 칸의 게임을 찾을 수 없어요.`;
      // 이미 담겨 있던 코스를 여는 경우에도 걸러야 한다 — 담을 때는 서버가 살아 있었을 수 있다.
      if (isFetchGame(game) && fetchBlocked) {
        return `${index + 1}번째 ${GAME_LABELS[game.name]}: ${AI_DOWN_MESSAGE}`;
      }
      if (game.requiresTopic && item.topicId === null) {
        return `${index + 1}번째 ${GAME_LABELS[game.name]}의 주제를 골라 주세요.`;
      }
      const topics = topicsByGameId[game.gameId];
      const topic = topics?.find((candidate) => candidate.topicId === item.topicId);
      // 몸말 1세트 = 전원이 한 번씩 출제 — 제시어가 인원수보다 적으면 세트가 중간에 끊긴다.
      if (topic && topic.missionCount < playerCount) {
        return `'${topic.name}' 주제의 제시어(${topic.missionCount}개)로는 ${playerCount}명이 한 번씩 출제할 수 없어요.`;
      }
    }
    return null;
  }, [draft, gamesById, topicsByGameId, playerCount, fetchBlocked]);

  const handleSave = async () => {
    if (validationMessage) {
      setLocalError(validationMessage);
      return;
    }
    const ok = await onSave(
      draft.map((item) => ({
        gameId: item.gameId,
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
            한 칸이 게임 1세트예요. 위에서 아래 순서로 진행되고, 같은 게임을 여러 번 담으면
            그만큼 여러 세트를 해요. 지금 인원은 {playerCount}명이에요.
          </p>

          <ol className="course-editor__list">
            {draft.map((item, index) => {
              const game = gamesById.get(item.gameId);
              if (!game) return null;
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
                    <small>{SET_UNIT_HINTS[game.name]}</small>
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
            {selectableGames.map((game) => {
              const blocked = isFetchGame(game) && fetchBlocked;
              return (
                <button
                  key={game.gameId}
                  type="button"
                  className="pap-pixel-btn"
                  onClick={() => addItem(game)}
                  disabled={draft.length >= MAX_COURSE_LENGTH || blocked}
                  title={blocked ? AI_DOWN_MESSAGE : undefined}
                >
                  + {GAME_LABELS[game.name]}
                </button>
              );
            })}
          </div>
          {/* AI 서버가 꺼져 있으면 왜 못 담는지 버튼 밖에도 적어준다 — 비활성 버튼의 title
              툴팁만으로는 눈에 안 띄어서 "버튼이 고장났다"로 읽힌다(대기방 시작 버튼과 같은 이유). */}
          {fetchBlocked && (
            <p className="course-editor__ai-down">
              {GAME_LABELS.FETCH_OBJECT}는 인식 서버가 꺼져 있어 지금 담을 수 없어요. 서버를 켠 뒤
              이 창을 다시 열면 담을 수 있어요.
            </p>
          )}
          {unsupportedGames.length > 0 && (
            <p className="course-editor__unsupported">
              준비 중: {unsupportedGames.map((game) => GAME_LABELS[game.name]).join(', ')}
            </p>
          )}

          {(localError ?? saveError ?? validationMessage) && (
            <p className="course-editor__error">{localError ?? saveError ?? validationMessage}</p>
          )}

          <div className="course-editor__actions">
            <button
              type="button"
              className="pap-pixel-btn"
              data-button-sound="cancel"
              onClick={onClose}
              disabled={saving}
            >
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
