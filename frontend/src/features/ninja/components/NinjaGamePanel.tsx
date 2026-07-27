import { useLocalParticipant, useParticipants } from '@livekit/components-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import './NinjaGamePanel.css';

const ATTACK_TARGET_TIMER_SECONDS = 30;

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

  const [otherParticipantIds, setOtherParticipantIds] = useState<Record<string, string>>({});

  // 다른 참가자들의 LiveKit identity → 결정적 참가자 UUID. 대상 지정 UI와 "시작" 버튼에 필요.
  useEffect(() => {
    const others = participants.filter((p) => p.identity !== localParticipant.identity);
    setOtherParticipantIds(Object.fromEntries(others.map((p) => [p.identity, p.identity])));
  }, [participants, localParticipant.identity]);

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

  // 게임 진입은 부모가 game:started 이벤트로 판단한다(이 패널은 게임 중에만 마운트됨). 이 패널은
  // 세션이 사라졌을 때(리셋/종료 정리) 부모에 알려 대기방으로 돌아가게 하는 역할만 한다. 단,
  // 마운트 직후 첫 폴링 전 round=null 상태로 대기방에 잘못 튕기지 않도록 "한 번이라도 활성이었을
  // 때만" 비활성을 통지한다.
  const wasActiveRef = useRef(false);
  useEffect(() => {
    if (gameStarted) {
      wasActiveRef.current = true;
      onActiveChange(true);
    } else if (wasActiveRef.current) {
      onActiveChange(false);
    }
  }, [gameStarted, onActiveChange]);

  // 대기방 단계에서는 이 패널을 통째로 숨긴다(손 인식 UI/게임 UI 없음) — 부모가 대기방을 대신 띄운다.
  if (!gameStarted) return null;

  return (
    <>
      <button
        className="ninja-reset-button"
        disabled={resetting}
        onClick={() => void resetGame()}
        title="진행 중인 게임을 지우고 대기방으로 되돌립니다"
      >
        {resetting ? '초기화 중...' : '🔄 게임 초기화'}
      </button>

    <div className="ninja-panel">
      <h2>닌자 게임</h2>

      {gameStarted && gameEnded && (
        <div className="ninja-panel__ranking">
          <p className="ninja-panel__ranking-title">🏆 게임 종료 — 최종 순위</p>
          <ol>
            {ranking.map((entry) => (
              <li key={entry.token} className={entry.token === myParticipantId ? 'ninja-panel__ranking-me' : ''}>
                {entry.rank}위 — {displayName(entry.token)}
              </li>
            ))}
          </ol>
        </div>
      )}

      {gameStarted && !gameEnded && (
        <>
          <p className="ninja-panel__round">
            라운드 {round} / {totalRounds}
            {roundTimerSeconds !== null && (
              <span className="ninja-panel__round-timer"> — 남은 시간 {roundTimerSeconds}s</span>
            )}
          </p>

          <ul className="ninja-panel__hp">
            {Object.entries(hp).map(([token, value]) => {
              const isAlive = alivePlayers.includes(token);
              const isMe = token === myParticipantId;
              return (
                <li key={token} className={isAlive ? '' : 'ninja-panel__hp--dead'}>
                  {isMe ? '나' : displayName(token)}: HP {value}
                  {!isAlive && ' (탈락)'}
                </li>
              );
            })}
          </ul>

          {requiredSkill && !completed && currentStep && (
            <div className="ninja-panel__current-gesture">
              <p className="ninja-panel__current-gesture-label">지금 취해야 할 손동작</p>
              <p className="ninja-panel__current-gesture-name">
                {currentStep.gestureLabelKr} <span>({currentStep.gestureName})</span>
              </p>
              <div className="ninja-panel__hold-bar">
                <div className="ninja-panel__hold-bar-fill" style={{ width: `${holdProgress * 100}%` }} />
              </div>
              <p className="ninja-panel__step-count">
                {stepIndex + 1} / {requiredSkill.gestures.length} 단계 (0.7초 이상, 신뢰도 80% 이상 유지)
              </p>
            </div>
          )}

          {requiredSkill && (
            <div className="ninja-panel__combo">
              <p>
                요구 스킬: <strong>{requiredSkill.skillName}</strong>
              </p>
              <ol>
                {requiredSkill.gestures.map((step, index) => (
                  <li
                    key={step.seq}
                    className={
                      index < stepIndex
                        ? 'ninja-panel__step--done'
                        : index === stepIndex
                          ? 'ninja-panel__step--active'
                          : ''
                    }
                  >
                    {step.gestureLabelKr} ({step.gestureName})
                  </li>
                ))}
              </ol>
              {completed && <p className="ninja-panel__combo-done">⚡ 콤보 완성! 공격 제출됨</p>}
            </div>
          )}

          {requiredSkill?.nextSkill && (
            <div className="ninja-panel__next-skill">
              <p className="ninja-panel__next-skill-label">다음 라운드 예고</p>
              <p className="ninja-panel__next-skill-name">{requiredSkill.nextSkill.skillName}</p>
              <p className="ninja-panel__next-skill-gestures">
                {requiredSkill.nextSkill.gestures.map((step) => step.gestureLabelKr).join(' → ')}
              </p>
            </div>
          )}

          {currentAttackerToken && !isMyAttack && (
            <p className="ninja-panel__attacker-info">
              {displayName(currentAttackerToken)} 님이 공격권을 먼저 획득했습니다
            </p>
          )}

          {isMyAttack && (
            <div className="ninja-panel__target-picker">
              <p className="ninja-panel__target-picker-title">⚔️ 공격권 획득!</p>
              {attackTimerSeconds !== null && (
                <div className="ninja-panel__timer">
                  <div
                    className="ninja-panel__timer-bar"
                    style={{ width: `${(attackTimerSeconds / ATTACK_TARGET_TIMER_SECONDS) * 100}%` }}
                  />
                  <span className="ninja-panel__timer-text">{attackTimerSeconds}s</span>
                </div>
              )}
              {(() => {
                const aliveOthers = Object.entries(otherParticipantIds).filter(([, id]) =>
                  alivePlayers.includes(id),
                );
                // 상대가 한 명뿐이면 골라 고를 것도 없어서 자동으로 공격된다(useNinjaRound 참고) —
                // 여기서는 버튼 대신 진행 중이라는 것만 알려준다.
                if (aliveOthers.length <= 1) {
                  return <p>상대를 자동으로 공격합니다...</p>;
                }
                return (
                  <>
                    <p>대상을 지정하세요</p>
                    {aliveOthers.map(([, id]) => (
                      <button key={id} onClick={() => void submitTarget(id)}>
                        {displayName(id)}
                      </button>
                    ))}
                  </>
                );
              })()}
            </div>
          )}

        </>
      )}

      {error && <p className="ninja-panel__error">{error}</p>}
    </div>
    </>
  );
}
