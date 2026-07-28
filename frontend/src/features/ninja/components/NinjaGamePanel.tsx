import { ParticipantTile, useLocalParticipant, useParticipants, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import './NinjaGamePanel.css';

const ATTACK_TARGET_TIMER_SECONDS = 5;

// 게임 시작(seed)은 이제 대기방(RoomLobby/VideoCallRoom)에서 트리거한다. 이 패널은 폴링으로
// 진행 상태만 읽어서 게임이 실제로 열려 있을 때만 렌더링하고, 그 활성 여부를 onActiveChange로
// 부모에 알려 부모가 대기방/게임 화면 전환과 손 인식 패널 on/off를 결정하게 한다.
interface NinjaGamePanelProps {
  roomId: string;
  gameId: number;
  accessToken: string;
  onActiveChange: (active: boolean) => void;
}

export function NinjaGamePanel({ roomId, gameId, accessToken, onActiveChange }: NinjaGamePanelProps) {
  const { localParticipant } = useLocalParticipant();
  const participants = useParticipants();
  const myParticipantId = localParticipant.identity || null;
  const comboEntry = useGestureBoardStore((state) => state.entries[localParticipant.identity]);
  const comboLabel = comboEntry?.comboLabel ?? null;
  const comboConfidence = comboEntry?.confidence ?? 0;

  const {
    round,
    totalRounds,
    alivePlayers,
    hp,
    currentAttackerToken,
    isMyAttack,
    roundTimerSeconds,
    attackTimerSeconds,
    requiredSkill,
    stepIndex,
    completed,
    holdProgress,
    ranking,
    gameEnded,
    error,
    submitTarget,
    resetGame,
    resetting,
  } = useNinjaRound(roomId, gameId, accessToken, myParticipantId, comboLabel, comboConfidence);

  // 카메라 트랙(없으면 placeholder) — 로비(LobbyScreen)와 동일한 방식으로 참가자별 실제 영상을 얻는다.
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });
  const trackByIdentity = useMemo(
    () => new Map(tracks.map((t) => [t.participant.identity, t] as const)),
    [tracks],
  );

  // participantId(=LiveKit identity) → 닉네임. 백엔드가 LiveKit 토큰 발급 시 token.setName(nickname)을
  // 해줘서 각 참가자 .name에 닉네임이 실려 온다. 화면엔 항상 닉네임만 쓰고 id는 내부용으로만 둔다.
  const nicknameByToken = useMemo(() => {
    const map: Record<string, string> = {};
    for (const p of participants) {
      if (p.name) map[p.identity] = p.name;
    }
    if (localParticipant.name) map[localParticipant.identity] = localParticipant.name;
    return map;
  }, [participants, localParticipant.identity, localParticipant.name]);

  const gameStarted = round !== null;
  const currentStep = requiredSkill?.gestures[stepIndex] ?? null;

  const displayName = (token: string) => {
    if (token === myParticipantId) return '나';
    return nicknameByToken[token] ?? '상대';
  };

  // 게임 활성 여부(폴링으로 세션이 열려 있는지)를 부모에 알려 대기방/게임 화면 전환에 쓴다.
  useEffect(() => {
    onActiveChange(gameStarted);
  }, [gameStarted, onActiveChange]);

  // HP가 폴링 사이에 줄어든 토큰을 잠깐 "피격" 상태로 표시(화면 흔들림 + 붉은 플래시).
  const prevHpRef = useRef<Record<string, number>>({});
  const [hitTokens, setHitTokens] = useState<Set<string>>(new Set());
  useEffect(() => {
    const prevHp = prevHpRef.current;
    const newlyHit = Object.entries(hp)
      .filter(([token, value]) => prevHp[token] !== undefined && value < prevHp[token])
      .map(([token]) => token);
    prevHpRef.current = hp;
    if (newlyHit.length === 0) return;

    setHitTokens((current) => new Set([...current, ...newlyHit]));
    const timer = setTimeout(() => {
      setHitTokens((current) => {
        const next = new Set(current);
        newlyHit.forEach((token) => next.delete(token));
        return next;
      });
    }, 500);
    return () => clearTimeout(timer);
  }, [hp]);

  // 대기방 단계에서는 이 패널을 통째로 숨긴다(손 인식 UI/게임 UI 없음) — 부모가 대기방을 대신 띄운다.
  if (!gameStarted) return null;

  // hp 레코드의 토큰 순서를 그대로 좌/우 절반으로 나눈다 — 백엔드가 seed 시 받은 참가자 순서를 유지.
  const tokens = Object.keys(hp);
  const mid = Math.ceil(tokens.length / 2);
  const leftTokens = tokens.slice(0, mid);
  const rightTokens = tokens.slice(mid);
  const playerIndex = new Map(tokens.map((token, i) => [token, i]));
  // 좌/우 컬럼이 같은 개수의 행을 갖게 해서 인원수와 상관없이 모든 타일 크기를 똑같이 유지한다
  // (3인전이면 양쪽 다 2행 — 오른쪽 두 번째 행은 빈 칸).
  const sideStyle = { '--ninja-rows': mid } as CSSProperties;

  const renderTile = (token: string) => {
    const index = playerIndex.get(token) ?? 0;
    const isAlive = alivePlayers.includes(token);
    const isMe = token === myParticipantId;
    const isAttacker = token === currentAttackerToken;
    const trackRef = trackByIdentity.get(token);
    const playerColor = `var(--pap-player-${(index % 4) + 1})`;
    const isHit = hitTokens.has(token);
    // 공격권을 쥔 내가 생존한 상대 캠을 아무 데나 클릭하면 그 상대를 공격 대상으로 지정한다.
    const isTargetable = isMyAttack && !isMe && isAlive;

    return (
      <div
        key={token}
        className={`pap-pixel-card ninja-battle__tile ${isAttacker ? 'ninja-battle__tile--active' : ''} ${
          !isAlive ? 'ninja-battle__tile--dead' : ''
        } ${isHit ? 'ninja-battle__tile--hit' : ''}`}
        style={{ borderColor: playerColor }}
      >
        <div className="ninja-battle__tile-top">
          <div className="ninja-battle__tile-id">
            <span className="ninja-battle__tile-badge" style={{ background: playerColor }}>
              {index + 1}
            </span>
            <span className="ninja-battle__tile-name">{isMe ? '나' : displayName(token)}</span>
          </div>
          <span className={`ninja-battle__tile-status ${isAttacker ? 'ninja-battle__tile-status--attack' : ''}`}>
            {!isAlive ? '탈락' : isAttacker ? '공격권 획득!' : ''}
          </span>
        </div>

        <div
          className={`ninja-battle__cam ${isTargetable ? 'ninja-battle__cam--targetable' : ''}`}
          role={isTargetable ? 'button' : undefined}
          tabIndex={isTargetable ? 0 : undefined}
          onClick={isTargetable ? () => void submitTarget(token) : undefined}
          onKeyDown={
            isTargetable
              ? (e) => {
                  if (e.key === 'Enter' || e.key === ' ') void submitTarget(token);
                }
              : undefined
          }
        >
          {isMe ? (
            <GesturePanel />
          ) : (
            trackRef && <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
          )}
          {isTargetable && <span className="ninja-battle__target-hint">클릭해서 공격</span>}
          {isHit && <span className="ninja-battle__hit-fx" aria-hidden="true" />}

          {/* 다른 참가자의 콤보 진행도는 서버가 안 내려줘서(내 손동작만 로컬로 추적) 내 타일에만 표시.
              캠 위 오버레이라 이 행이 있고 없고가 타일 높이에 영향을 주지 않는다. */}
          {isMe && requiredSkill && (
            <div className="ninja-battle__progress-row">
              <p className="ninja-battle__row-label">입력 진행도</p>
              <div className="ninja-battle__progress-icons">
                {requiredSkill.gestures.map((g, i) => (
                  <span
                    key={g.seq}
                    className={`ninja-battle__progress-icon ${
                      i < stepIndex || completed
                        ? 'ninja-battle__progress-icon--done'
                        : i === stepIndex
                          ? 'ninja-battle__progress-icon--active'
                          : ''
                    }`}
                    title={g.gestureLabelKr}
                  >
                    {g.gestureLabelKr.slice(0, 1)}
                  </span>
                ))}
                <span className="ninja-battle__progress-count">
                  {completed ? requiredSkill.gestures.length : stepIndex}/{requiredSkill.gestures.length}
                </span>
              </div>
            </div>
          )}
        </div>

        <div className="ninja-battle__hp-row">
          <span>❤️ {hp[token] ?? 0} / 100</span>
          <span className="ninja-battle__hp-bar">
            <span className="ninja-battle__hp-bar-fill" style={{ width: `${Math.max(0, hp[token] ?? 0)}%` }} />
          </span>
        </div>
      </div>
    );
  };

  return (
    <div className="ninja-screen">
      <header className="ninja-battle__header">
        <img className="ninja-battle__logo pap-pixel-img" src="/assets/cam-on-logo.png" alt="CAM, ON!" />
        <button
          type="button"
          className="pap-pixel-btn lobby-btn-sm"
          disabled={resetting}
          onClick={() => void resetGame()}
          title="진행 중인 게임을 지우고 대기방으로 되돌립니다"
        >
          {resetting ? '초기화 중...' : '게임 초기화'}
        </button>
      </header>

      {gameEnded ? (
        <div className="pap-pixel-card ninja-battle__ranking">
          <p className="pap-pixel-title ninja-battle__ranking-title">🏆 게임 종료 — 최종 순위</p>
          <ol>
            {ranking.map((entry) => (
              <li key={entry.token} className={entry.token === myParticipantId ? 'ninja-battle__ranking-me' : ''}>
                {entry.rank}위 — {displayName(entry.token)}
              </li>
            ))}
          </ol>
        </div>
      ) : (
        <div className="ninja-battle__layout">
          <div className="ninja-battle__side" style={sideStyle}>
            {leftTokens.map(renderTile)}
          </div>

          <div className="ninja-battle__center">
            <div className="ninja-battle__round-row">
              <p className="ninja-battle__round-label">
                ROUND {round} / {totalRounds}
              </p>
              {roundTimerSeconds !== null && (
                <p className="ninja-battle__timer-big">{String(roundTimerSeconds).padStart(2, '0')}s</p>
              )}
            </div>

            {requiredSkill && (
              <div className="pap-pixel-card ninja-battle__skill-card">
                <p className="ninja-battle__skill-label">현재 술법</p>
                <div className="ninja-battle__skill-name-row">
                  <span className="ninja-battle__skill-icon">🔥</span>
                  <span className="ninja-battle__skill-name">{requiredSkill.skillName}</span>
                </div>
                <p className="ninja-battle__skill-label">필요한 인 순서</p>
                <div className="ninja-battle__sequence">
                  {requiredSkill.gestures.map((g, i) => (
                    <span
                      key={g.seq}
                      className={`ninja-battle__sequence-icon ${
                        i < stepIndex || completed
                          ? 'ninja-battle__sequence-icon--done'
                          : i === stepIndex
                            ? 'ninja-battle__sequence-icon--active'
                            : ''
                      }`}
                    >
                      {g.gestureLabelKr}
                    </span>
                  ))}
                </div>
                {completed ? (
                  <p className="ninja-battle__skill-hint">⚡ 콤보 완성! 공격 제출됨</p>
                ) : currentStep ? (
                  <>
                    <div className="ninja-battle__hold-bar">
                      <div className="ninja-battle__hold-bar-fill" style={{ width: `${holdProgress * 100}%` }} />
                    </div>
                    <p className="ninja-battle__skill-hint">
                      {currentStep.gestureLabelKr} 유지 중 ({stepIndex + 1}/{requiredSkill.gestures.length}, 0.7초·신뢰도 80% 이상)
                    </p>
                  </>
                ) : (
                  <p className="ninja-battle__skill-hint">위 순서대로 입력하세요!</p>
                )}
              </div>
            )}

            <div className="pap-pixel-card ninja-battle__attack-card">
              {isMyAttack ? (
                <>
                  <p className="ninja-battle__attack-title">⚔️ 공격권 획득!</p>
                  {attackTimerSeconds !== null && (
                    <div className="ninja-battle__attack-timer">
                      <div
                        className="ninja-battle__attack-timer-fill"
                        style={{ width: `${(attackTimerSeconds / ATTACK_TARGET_TIMER_SECONDS) * 100}%` }}
                      />
                      <span className="ninja-battle__attack-timer-text">{attackTimerSeconds}s</span>
                    </div>
                  )}
                  {(() => {
                    const aliveOthers = tokens.filter((t) => t !== myParticipantId && alivePlayers.includes(t));
                    if (aliveOthers.length <= 1) {
                      return <p className="ninja-battle__idle-hint">상대를 자동으로 공격합니다...</p>;
                    }
                    return (
                      <p className="ninja-battle__idle-hint">공격할 상대의 캠 화면을 클릭하세요! (미선택 시 자동 공격)</p>
                    );
                  })()}
                </>
              ) : currentAttackerToken ? (
                <p className="ninja-battle__attacker-info">{displayName(currentAttackerToken)} 님이 공격권을 먼저 획득했습니다</p>
              ) : null}
            </div>
          </div>

          <div className="ninja-battle__side">{rightTokens.map(renderTile)}</div>
        </div>
      )}

      {error && <p className="ninja-battle__error">{error}</p>}
    </div>
  );
}
