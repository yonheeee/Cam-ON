import { useLocalParticipant, useParticipants } from '@livekit/components-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import { NinjaEffectOverlay } from './NinjaEffectOverlay';
import './NinjaGamePanel.css';

const ATTACK_TARGET_TIMER_SECONDS = 30;

// 게임 시작은 이제 대기방(LobbyScreen/VideoCallRoom)에서 트리거한다. 이 패널은 폴링으로
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
    exchange,
    sessionTotals,
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
    attackAccepted,
    ranking,
    gameEnded,
    error,
    submitTarget,
    isIntermission,
    inEffectPlayback,
    inCountdown,
    countdownSeconds,
    lastAttack,
    roundResult,
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
    <div className="ninja-panel">
      <h2>손은 눈보다 빠르다</h2>

      {gameStarted && gameEnded && (
        <div className="ninja-panel__ranking">
          <p className="ninja-panel__ranking-title">🏆 게임 종료 — 최종 순위 (누적 점수)</p>
          <ol>
            {ranking.map((entry) => (
              <li key={entry.token} className={entry.token === myParticipantId ? 'ninja-panel__ranking-me' : ''}>
                {entry.rank}위 — {displayName(entry.token)} · {sessionTotals[entry.token] ?? 0}점
              </li>
            ))}
          </ol>
        </div>
      )}

      {gameStarted && !gameEnded && (
        <>
          <p className="ninja-panel__round">
            라운드 {round} / {totalRounds}
            {exchange !== null && <span className="ninja-panel__exchange"> · {exchange}번째 교환</span>}
            {!isIntermission && roundTimerSeconds !== null && (
              <span className="ninja-panel__round-timer"> — 남은 시간 {roundTimerSeconds}s</span>
            )}
          </p>

          {/* 판을 가로질러 누적된 점수 — 최후 1인 판이 끝날 때마다 5/4/3/2점이 쌓인다. */}
          {Object.keys(sessionTotals).length > 0 && (
            <ul className="ninja-panel__totals">
              {Object.entries(sessionTotals)
                .sort(([, a], [, b]) => b - a)
                .map(([token, points]) => (
                  <li key={token} className={token === myParticipantId ? 'ninja-panel__totals-me' : ''}>
                    {displayName(token)}: {points}점
                  </li>
                ))}
            </ul>
          )}

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

          {isIntermission && (
            <div className="ninja-panel__intermission">
              {/* 판이 끝난 인터미션이면(roundResult 존재) 이펙트 대신 그 판의 순위+획득 점수를 보여준다. */}
              {roundResult && roundResult.length > 0 ? (
                <div className="ninja-panel__round-result">
                  {inEffectPlayback && requiredSkill && <NinjaEffectOverlay effect={requiredSkill.effect} />}
                  <p className="ninja-panel__round-result-title">🥷 라운드 {round} 결과</p>
                  <ol>
                    {roundResult.map((entry) => (
                      <li
                        key={entry.token}
                        className={entry.token === myParticipantId ? 'ninja-panel__ranking-me' : ''}
                      >
                        {entry.rank}위 — {displayName(entry.token)}
                        <span className="ninja-panel__round-result-points"> +{entry.points}점</span>
                      </li>
                    ))}
                  </ol>
                  {inCountdown && countdownSeconds !== null && (
                    <p className="ninja-panel__round-result-next">다음 라운드 시작까지 {countdownSeconds}</p>
                  )}
                </div>
              ) : (
                <>
                  {inEffectPlayback && requiredSkill && (
                    <>
                      <NinjaEffectOverlay effect={requiredSkill.effect} />
                      <p className="ninja-panel__intermission-title">
                        {lastAttack ? (
                          <>
                            {displayName(lastAttack.attackerToken)} → {displayName(lastAttack.targetToken)}
                            {' · '}
                            {requiredSkill.skillName} ({lastAttack.damage} 데미지)
                            {lastAttack.targetEliminated && ' · 탈락!'}
                          </>
                        ) : (
                          '교환 종료'
                        )}
                      </p>
                    </>
                  )}
                  {inCountdown && (
                    <div className="ninja-panel__countdown">
                      <p className="ninja-panel__countdown-label">다음 교환까지</p>
                      <p className="ninja-panel__countdown-number">{countdownSeconds}</p>
                    </div>
                  )}
                  {!inEffectPlayback && !inCountdown && (
                    <p className="ninja-panel__intermission-title">정리 중...</p>
                  )}
                </>
              )}
            </div>
          )}

          {!isIntermission && requiredSkill && !completed && currentStep && (
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

          {!isIntermission && requiredSkill && (
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
              {/* "공격 제출됨"이 아니라 서버가 공격권 선점을 수락(200)했을 때만 성공으로 표시한다.
                  콤보만 완성하고 아직 응답 전이면 "전송 중", 남이 먼저 선점했으면 아래 attacker-info로 표시. */}
              {completed && !attackAccepted && !currentAttackerToken && (
                <p className="ninja-panel__combo-done">⚡ 콤보 완성! 공격 전송 중…</p>
              )}
              {attackAccepted && <p className="ninja-panel__combo-done">⚡ 공격권 획득!</p>}
            </div>
          )}

          {!isIntermission && requiredSkill?.nextSkill && (
            <div className="ninja-panel__next-skill">
              <p className="ninja-panel__next-skill-label">다음 라운드 예고</p>
              <p className="ninja-panel__next-skill-name">{requiredSkill.nextSkill.skillName}</p>
              <p className="ninja-panel__next-skill-gestures">
                {requiredSkill.nextSkill.gestures.map((step) => step.gestureLabelKr).join(' → ')}
              </p>
            </div>
          )}

          {!isIntermission && currentAttackerToken && !isMyAttack && (
            <p className="ninja-panel__attacker-info">
              {displayName(currentAttackerToken)} 님이 공격권을 먼저 획득했습니다
            </p>
          )}

          {!isIntermission && isMyAttack && (
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
