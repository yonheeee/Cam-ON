import { useLocalParticipant, useParticipants } from '@livekit/components-react';
import { useEffect, useState } from 'react';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import { resolveParticipantId, useParticipantId } from '../lib/participantId';
import './NinjaGamePanel.css';

const DEFAULT_TOTAL_ROUNDS = 5;
const ATTACK_TARGET_TIMER_SECONDS = 30;

// 대기방/방장 도메인이 아직 없어서 room은 고정 테스트 id(ninjaApi.TEST_ROOM_ID)로 취급하고,
// "게임 시작"도 방장 권한 체크 없이 아무나 누르면 바로 세션이 열린다. 방/코스 도메인이
// 완성되면: (1) TEST_ROOM_ID 대신 실제 roomId를 props로 받고, (2) 이 안의 "시작" 버튼과
// ninjaApi.seed 호출을 지우고 그 도메인의 게임 시작 흐름이 대신하면 된다 — useNinjaRound
// 이하는 안 바뀐다.
export function NinjaGamePanel() {
  const { localParticipant } = useLocalParticipant();
  const participants = useParticipants();
  const { id: myParticipantId, error: participantIdError } = useParticipantId(localParticipant.identity);
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
    seeding,
    submitTarget,
    seed,
    resetGame,
    resetting,
  } = useNinjaRound(myParticipantId, comboLabel, comboConfidence);

  const [otherParticipantIds, setOtherParticipantIds] = useState<Record<string, string>>({});

  // 다른 참가자들의 LiveKit identity → 결정적 참가자 UUID. 대상 지정 UI와 "시작" 버튼에 필요.
  useEffect(() => {
    let cancelled = false;
    const others = participants.filter((p) => p.identity !== localParticipant.identity);
    Promise.all(others.map(async (p) => [p.identity, await resolveParticipantId(p.identity)] as const)).then(
      (entries) => {
        if (!cancelled) setOtherParticipantIds(Object.fromEntries(entries));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [participants, localParticipant.identity]);

  const gameStarted = round !== null;
  const currentStep = requiredSkill?.gestures[stepIndex] ?? null;
  const attackerIdentity =
    currentAttackerToken != null
      ? Object.entries(otherParticipantIds).find(([, id]) => id === currentAttackerToken)?.[0]
      : undefined;

  const displayName = (token: string) => {
    if (token === myParticipantId) return '나';
    return Object.entries(otherParticipantIds).find(([, id]) => id === token)?.[0] ?? token.slice(0, 8);
  };

  return (
    <>
      <button
        className="ninja-reset-button"
        disabled={resetting}
        onClick={() => void resetGame()}
        title="진행 중인 게임을 지우고 다시 시작 버튼이 뜨는 상태로 되돌립니다"
      >
        {resetting ? '초기화 중...' : '🔄 게임 초기화'}
      </button>

    <div className="ninja-panel">
      <h2>닌자 게임 (테스트방 고정)</h2>

      <p className="ninja-panel__debug">
        identity: {localParticipant.identity || '(아직 없음)'} / participantId:{' '}
        {myParticipantId ? myParticipantId.slice(0, 8) : '(계산 중...)'}
      </p>
      {participantIdError && <p className="ninja-panel__error">참가자 ID 계산 실패: {participantIdError}</p>}

      {!gameStarted && (
        <button
          className="ninja-panel__start"
          disabled={seeding || !myParticipantId}
          onClick={() => {
            const tokens = [myParticipantId, ...Object.values(otherParticipantIds)].filter(
              (token): token is string => Boolean(token),
            );
            // 인원수(최소 2명) 검증은 서버가 최종적으로 하고, 부족하면 error 메시지로 보여준다 —
            // 버튼 자체는 누르면 바로 시도되게 막지 않는다.
            void seed(tokens, DEFAULT_TOTAL_ROUNDS);
          }}
        >
          {seeding ? '시작 중...' : !myParticipantId ? '참가자 ID 준비 중...' : '닌자게임 시작 (테스트)'}
        </button>
      )}

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
                  {isMe ? '나' : token.slice(0, 8)}: HP {value}
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
              {attackerIdentity ?? currentAttackerToken.slice(0, 8)} 님이 공격권을 먼저 획득했습니다
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
                    {aliveOthers.map(([identity, id]) => (
                      <button key={id} onClick={() => void submitTarget(id)}>
                        {identity}
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
