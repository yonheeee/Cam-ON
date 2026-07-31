import { useMemo, useState } from 'react';
import { useAiHealth } from '../../fetch/hooks/useAiHealth';
import {
  GAME_LABELS,
  type CatalogGame,
  type CatalogTopic,
  type Course,
  type CourseItemInput,
  type GameName,
} from '../api/courseApi';
import './GameSetupScreen.css';

const MAX_COURSE_LENGTH = 7; // 백엔드 CourseService.MAX_COURSE_LENGTH와 같은 값

const AI_DOWN_MESSAGE = '인식 서버에 연결할 수 없어 지금은 담을 수 없어요.';

// 게임별 소개 — Figma `Game Setup · Accordion` 시안 원문.
// (서버 description과 별개로, 이 화면 전용의 요약·진행 단계·스펙 표기)
const GAME_META: Record<
  GameName,
  {
    code: string;
    summary: string;
    steps: { title: string; desc: string }[];
    round: string;
    device: string;
    /** 세트 카드에 표시하는 분량 규칙 (Figma `Game Set / Compact Rule`) */
    compactRule: string;
  }
> = {
  FETCH_OBJECT: {
    code: 'BOX',
    summary: '미션 물건을 확인하고, 제한 시간 안에 가장 먼저 카메라에 보여주세요.',
    steps: [
      { title: '미션 확인', desc: '화면에 제시된 물건을 확인해요.' },
      { title: '물건 찾기', desc: '제한 시간 안에 물건을 가져와요.' },
      { title: 'AI 판정', desc: '인식 성공 순서대로 점수를 얻어요.' },
    ],
    round: '5라운드',
    device: '카메라 · 사물 인식',
    compactRule: '5라운드',
  },
  NINJA: {
    code: 'HAND',
    summary: '손동작을 먼저 완성해 공격권을 얻고 상대의 HP를 깎아요',
    steps: [
      { title: '동작 확인', desc: '화면에 제시된 손동작 순서를 확인해요.' },
      { title: '빠르게 완성', desc: '손동작을 순서대로 가장 먼저 따라 해요.' },
      { title: '상대 공격', desc: '공격권을 얻어 상대를 선택하고 HP를 깎아요.' },
    ],
    round: '최후 1인까지',
    device: '카메라 · 손동작 인식',
    compactRule: '최종 1인까지',
  },
  CHARADES: {
    code: 'ACT',
    summary: '한 명은 몸으로 표현하고 나머지는 정답을 입력해 맞혀요',
    steps: [
      { title: '제시어 확인', desc: '표현자에게만 제시어가 보여요.' },
      { title: '몸으로 표현', desc: '말하지 않고 몸짓으로 설명해요.' },
      { title: '정답 입력', desc: '나머지 참여자는 정답을 입력해 맞혀요.' },
    ],
    round: 'N라운드',
    device: '카메라 · 포즈 인식',
    compactRule: 'N라운드',
  },
};

const BackIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M11 5.5 4.5 12l6.5 6.5M5.5 12H20"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const ForwardIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M13 5.5 19.5 12 13 18.5M18.5 12H4"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const AddIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M12 4.5v15M4.5 12h15" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" />
  </svg>
);

const TrashIcon = (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path
      d="M4.5 6.5h15M9.5 6V4.5h5V6M7 6.5l1 13h8l1-13M10 10v6M14 10v6"
      stroke="currentColor"
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

interface GameSetupScreenProps {
  games: CatalogGame[];
  course: Course | null;
  topicsByGameId: Record<number, CatalogTopic[]>;
  playerCount: number;
  saving: boolean;
  saveError: string | null;
  onSave: (items: CourseItemInput[]) => Promise<boolean>;
  onClose: () => void;
}

interface DraftItem {
  /** React key용 안정 id — index를 key로 쓰면 드래그 재정렬 때 DOM이 재생성돼
      dragend가 유실되고 반투명(--dragging) 상태가 풀리지 않는다. */
  uid: number;
  gameId: number;
  topicId: number | null;
}

let nextUid = 1;
const makeUid = () => nextUid++;

// 게임 구성 전체 화면 — CourseEditorModal(팝업)을 대체한다.
// 좌: 세트 큐(드래그로 순서 변경) / 우: 게임 리스트 아코디언 / 하단: 저장.
// 같은 게임을 여러 번 담으면 그만큼 여러 세트를 한다. 저장은 전체 교체(PUT).
export function GameSetupScreen({
  games,
  course,
  topicsByGameId,
  playerCount,
  saving,
  saveError,
  onSave,
  onClose,
}: GameSetupScreenProps) {
  const [draft, setDraft] = useState<DraftItem[]>(
    () =>
      course?.items.map((item) => ({
        uid: makeUid(),
        gameId: item.gameId,
        topicId: item.topicId,
      })) ?? [],
  );
  const [localError, setLocalError] = useState<string | null>(null);
  // 아코디언은 한 번에 하나만 펼친다 (시안과 동일). 기본은 첫 게임.
  const [expandedGameId, setExpandedGameId] = useState<number | null>(
    () => games.find((game) => game.supported)?.gameId ?? null,
  );
  // 드래그 추적은 index가 아니라 uid로 — 재정렬로 index가 계속 바뀌기 때문
  const [dragUid, setDragUid] = useState<number | null>(null);

  const gamesById = useMemo(() => new Map(games.map((game) => [game.gameId, game])), [games]);
  const listedGames = games.filter((game) => game.supported);
  const unsupportedGames = games.filter((game) => !game.supported);

  // 물건 가져오기는 AI 서버 인식에 의존 — 서버가 죽어 있으면 담는 시점에 막는다.
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
    setDraft((prev) => [...prev, { uid: makeUid(), gameId: game.gameId, topicId: null }]);
  };

  const removeItem = (index: number) => {
    setLocalError(null);
    setDraft((prev) => prev.filter((_, i) => i !== index));
  };

  const moveItem = (from: number, to: number) => {
    if (to < 0 || to >= draft.length || from === to) return;
    setDraft((prev) => {
      const next = [...prev];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      return next;
    });
  };

  const changeTopic = (index: number, topicId: number | null) => {
    setDraft((prev) => prev.map((item, i) => (i === index ? { ...item, topicId } : item)));
  };

  // 저장 전에 프론트에서 잡을 수 있는 문제를 먼저 보여준다 (서버도 같은 것을 검증한다 —
  // 단 AI 서버 생존 여부는 Spring이 알 수 없으므로 이 검증만 프론트 단독이다).
  const validationMessage = useMemo(() => {
    if (draft.length === 0) return '게임을 최소 1세트 담아야 해요.';
    for (const [index, item] of draft.entries()) {
      const game = gamesById.get(item.gameId);
      if (!game) return `${index + 1}번째 칸의 게임을 찾을 수 없어요.`;
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
    const ok = await onSave(draft.map((item) => ({ gameId: item.gameId, topicId: item.topicId })));
    if (ok) onClose();
  };

  const footerMessage = localError ?? saveError;

  return (
    <div className="game-setup" role="dialog" aria-label="게임 구성">
      <div className="game-setup__bg-bottom" />
      <img className="game-setup__logo" src="/assets/cam-on-logo-v3.png" alt="CAM, ON!" />

      <header className="game-setup__header">
        <span className="game-setup__breadcrumb">대기방&nbsp;&nbsp;/&nbsp;&nbsp;게임 구성</span>
        <h1 className="game-setup__title">게임 구성</h1>
        <p className="game-setup__subtitle">오른쪽 게임 목록에서 게임을 추가해 세트를 구성하세요.</p>
        <button type="button" className="game-setup__back" data-button-sound="cancel" onClick={onClose}>
          {BackIcon}
          돌아가기
        </button>
      </header>

      <div className="game-setup__body">
        {/* ---------- 좌: 세트 구성 ---------- */}
        <section className="game-setup__panel" aria-label="세트 구성">
          <div className="game-setup__panel-head">
            <h2 className="game-setup__panel-title">세트 구성</h2>
            <span className="game-setup__set-counter">
              {draft.length} / {MAX_COURSE_LENGTH}세트
            </span>
          </div>

          <ol className="game-setup__sets">
            {draft.map((item, index) => {
              const game = gamesById.get(item.gameId);
              if (!game) return null;
              const meta = GAME_META[game.name];
              const topics = topicsByGameId[game.gameId] ?? [];
              const playersOk = playerCount >= game.minPlayers && playerCount <= game.maxPlayers;
              return (
                <li
                  key={item.uid}
                  className={`game-setup__set${dragUid === item.uid ? ' game-setup__set--dragging' : ''}`}
                  draggable
                  tabIndex={0}
                  aria-label={`${index + 1}세트 ${GAME_LABELS[game.name]} — 드래그하거나 방향키로 순서 변경`}
                  onDragStart={(e) => {
                    setDragUid(item.uid);
                    e.dataTransfer.effectAllowed = 'move';
                  }}
                  onDragEnter={() => {
                    // 드래그 중인 카드가 다른 카드 위로 오면 즉시 자리를 바꿔 미리보기처럼 보여준다
                    if (dragUid === null || dragUid === item.uid) return;
                    const from = draft.findIndex((d) => d.uid === dragUid);
                    if (from === -1) return;
                    moveItem(from, index);
                  }}
                  onDragOver={(e) => e.preventDefault()}
                  onDragEnd={() => setDragUid(null)}
                  onDrop={() => setDragUid(null)}
                  onKeyDown={(e) => {
                    // 키보드 접근성 — 카드에 포커스를 두고 ↑/↓로 순서 변경
                    if (e.key === 'ArrowUp') {
                      e.preventDefault();
                      moveItem(index, index - 1);
                    } else if (e.key === 'ArrowDown') {
                      e.preventDefault();
                      moveItem(index, index + 1);
                    }
                  }}
                >
                  <span className="game-setup__set-handle" aria-hidden="true">
                    ⠿
                  </span>
                  <span className="game-setup__set-seq">
                    {String(index + 1).padStart(2, '0')} 세트
                  </span>
                  <strong className="game-setup__set-name">{GAME_LABELS[game.name]}</strong>

                  {game.requiresTopic ? (
                    <select
                      className={`game-setup__topic${
                        item.topicId === null ? ' game-setup__topic--missing' : ''
                      }`}
                      value={item.topicId ?? ''}
                      onChange={(e) =>
                        changeTopic(index, e.target.value === '' ? null : Number(e.target.value))
                      }
                      onPointerDown={(e) => e.stopPropagation()}
                      aria-label="주제 선택"
                    >
                      <option value="">주제 선택</option>
                      {topics.map((topic) => (
                        <option key={topic.topicId} value={topic.topicId}>
                          {topic.name} ({topic.missionCount}개)
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span
                      className={`game-setup__set-rule${
                        playersOk ? '' : ' game-setup__set-rule--warn'
                      }`}
                    >
                      {playersOk
                        ? meta.compactRule
                        : `${game.minPlayers}~${game.maxPlayers}명이어야 진행돼요`}
                    </span>
                  )}

                  <button
                    type="button"
                    className="game-setup__set-delete"
                    data-button-sound="cancel"
                    onClick={() => removeItem(index)}
                    title="세트 삭제"
                    aria-label={`${index + 1}세트 삭제`}
                  >
                    {TrashIcon}
                  </button>
                </li>
              );
            })}
            {draft.length === 0 && (
              <li className="game-setup__sets-empty">
                오른쪽 게임 목록에서 + 버튼을 눌러
                <br />
                세트를 추가해 주세요.
              </li>
            )}
          </ol>
        </section>

        {/* ---------- 우: 게임 리스트 ---------- */}
        <section className="game-setup__panel" aria-label="게임 리스트">
          <div className="game-setup__panel-head">
            <h2 className="game-setup__panel-title">게임 리스트</h2>
          </div>
          <p className="game-setup__panel-hint">게임 카드를 눌러 설명을 확인하고 세트에 추가하세요.</p>

          <div className="game-setup__games">
            {listedGames.map((game) => {
              const meta = GAME_META[game.name];
              const expanded = expandedGameId === game.gameId;
              const blocked = isFetchGame(game) && fetchBlocked;
              const full = draft.length >= MAX_COURSE_LENGTH;
              return (
                <article
                  key={game.gameId}
                  className={`game-setup__game${expanded ? '' : ' game-setup__game--collapsible'}`}
                >
                  <div
                    className="game-setup__game-head"
                    role="button"
                    tabIndex={0}
                    aria-expanded={expanded}
                    onClick={() => setExpandedGameId(expanded ? null : game.gameId)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        setExpandedGameId(expanded ? null : game.gameId);
                      }
                    }}
                  >
                    <span className="game-setup__game-icon">{meta.code}</span>
                    <h3 className="game-setup__game-title">{GAME_LABELS[game.name]}</h3>
                    <p
                      className={`game-setup__game-summary${
                        blocked ? ' game-setup__game-summary--warn' : ''
                      }`}
                    >
                      {blocked ? AI_DOWN_MESSAGE : meta.summary}
                    </p>
                    <button
                      type="button"
                      className="game-setup__game-add"
                      onClick={(e) => {
                        e.stopPropagation();
                        addItem(game);
                      }}
                      disabled={blocked || full}
                      title={
                        blocked
                          ? AI_DOWN_MESSAGE
                          : full
                            ? `최대 ${MAX_COURSE_LENGTH}세트까지 담을 수 있어요`
                            : '세트에 추가'
                      }
                      aria-label={`${GAME_LABELS[game.name]} 세트에 추가`}
                    >
                      {AddIcon}
                    </button>
                  </div>

                  {expanded && (
                    <div className="game-setup__game-detail">
                      <div className="game-setup__divider" />
                      <div className="game-setup__detail-grid">
                        <div className="game-setup__steps">
                          {meta.steps.map((step, i) => (
                            <div key={step.title} className="game-setup__step">
                              <span className="game-setup__step-no">
                                {String(i + 1).padStart(2, '0')}
                              </span>
                              <span className="game-setup__step-title">{step.title}</span>
                              <span className="game-setup__step-desc">{step.desc}</span>
                            </div>
                          ))}
                        </div>
                        <div className="game-setup__info">
                          <div className="game-setup__info-row">
                            <span className="game-setup__info-label">ROUND</span>
                            <span className="game-setup__info-value">{meta.round}</span>
                          </div>
                          <div className="game-setup__info-row">
                            <span className="game-setup__info-label">PLAYER</span>
                            <span className="game-setup__info-value">
                              {game.minPlayers}~{game.maxPlayers}명
                            </span>
                          </div>
                          <div className="game-setup__info-row">
                            <span className="game-setup__info-label">DEVICE</span>
                            <span className="game-setup__info-value">{meta.device}</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </article>
              );
            })}
            {unsupportedGames.length > 0 && (
              <p className="game-setup__panel-hint">
                준비 중: {unsupportedGames.map((game) => GAME_LABELS[game.name]).join(', ')}
              </p>
            )}
          </div>

          <div className="game-setup__footer">
            <p
              className={`game-setup__footer-note${
                footerMessage ? ' game-setup__footer-note--error' : ''
              }`}
            >
              {footerMessage ?? '게임 시작 전까지 방장만 구성을 변경할 수 있어요.'}
            </p>
            <button
              type="button"
              className="game-setup__save"
              onClick={() => void handleSave()}
              disabled={saving || !!validationMessage}
              title={validationMessage ?? undefined}
            >
              {saving ? '저장 중...' : '저장하고 대기방으로'}
              {ForwardIcon}
            </button>
          </div>
        </section>
      </div>
    </div>
  );
}
