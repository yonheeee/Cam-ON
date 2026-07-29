import {
  ParticipantTile,
  useDataChannel,
  useLocalParticipant,
  useParticipants,
  useTracks,
} from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useMemo, useRef } from 'react';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../../gesture/lib/gestureBroadcast';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import { skillEffect } from '../lib/skillEffects';
import { NinjaEffectOverlay } from './NinjaEffectOverlay';
import './NinjaBattleScreen.css';

const ATTACK_TARGET_TIMER_SECONDS = 30;
const MAX_HP = 100;

// 닌자 전투 화면. 대기방(LobbyScreen)/몸으로말해요(CharadesGamePanel)와 같은 방식으로 이 화면이
// 캠 타일까지 직접 그린다 — 그래서 HP·점수·공격권·이펙트를 "그 사람 타일 위"에 얹을 수 있다.
// (이전 NinjaGamePanel은 VideoConference 옆에 떠 있는 텍스트 패널이라 타일과 값을 묶을 수 없었다.)
interface NinjaBattleScreenProps {
  roomId: string;
  gameId: number;
  accessToken: string;
  onActiveChange: (active: boolean) => void;
}

export function NinjaBattleScreen({
  roomId,
  gameId,
  accessToken,
  onActiveChange,
}: NinjaBattleScreenProps) {
  const { localParticipant } = useLocalParticipant();
  const participants = useParticipants();
  const myId = localParticipant.identity || null;

  const comboEntry = useGestureBoardStore((state) => state.entries[localParticipant.identity]);
  const entries = useGestureBoardStore((state) => state.entries);
  const setEntry = useGestureBoardStore((state) => state.setEntry);

  // 남의 손동작 판정 수신. 예전엔 GestureBoard(좌하단 고정 디버그 박스)가 이 구독을 들고 있었는데,
  // 판정을 각 캠 타일에 표시하려고 보드를 없애면서 구독만 이리로 옮겼다.
  useDataChannel(GESTURE_RESULT_TOPIC, (msg) => {
    try {
      const payload = JSON.parse(new TextDecoder().decode(msg.payload)) as GestureResultPayload;
      setEntry(payload.identity, payload);
    } catch {
      // 잘못된 payload는 무시
    }
  });

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
    boutResult,
  } = useNinjaRound(roomId, gameId, accessToken, myId, comboEntry?.comboLabel ?? null, comboEntry?.confidence ?? 0);

  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });
  const trackByIdentity = useMemo(
    () => new Map(tracks.map((t) => [t.participant.identity, t])),
    [tracks],
  );

  // 타일 순서와 플레이어 색은 모든 참가자 화면에서 같아야 한다(내 화면에선 2P인 사람이 남의 화면에선
  // 3P면 색으로 소통이 안 된다). LiveKit participants 배열 순서는 클라이언트마다 다를 수 있어서
  // identity 문자열로 정렬해 결정적으로 만든다.
  const seats = useMemo(
    () => [...participants].sort((a, b) => a.identity.localeCompare(b.identity)),
    [participants],
  );

  const nicknameOf = (id: string) =>
    participants.find((p) => p.identity === id)?.name || (id === myId ? '나' : '상대');

  const gameStarted = round !== null;

  // 세션이 사라지면(코스가 다음 게임으로 넘어감) 부모에 알려 화면을 넘긴다. 마운트 직후 첫 폴링
  // 전 round=null 상태로 잘못 튕기지 않도록 한 번이라도 활성이었을 때만 비활성을 통지한다.
  const wasActiveRef = useRef(false);
  useEffect(() => {
    if (gameStarted) {
      wasActiveRef.current = true;
      onActiveChange(true);
    } else if (wasActiveRef.current) {
      onActiveChange(false);
    }
  }, [gameStarted, onActiveChange]);

  if (!gameStarted) return null;

  // 이펙트는 맞은 사람 타일에서 재생한다 — 데미지가 "누구에게" 들어갔는지가 화면에서 바로 읽힌다.
  const effectTargetId = inEffectPlayback ? (lastAttack?.targetToken ?? null) : null;
  const pixelEffect = effectTargetId ? skillEffect(lastAttack?.skillId ?? requiredSkill?.skillId) : null;

  return (
    <div className="ninja-screen">
      <header className="ninja-screen__topbar">
        <img className="ninja-screen__logo pap-pixel-img" src="/assets/cam-on-logo.png" alt="CAM, ON!" />
        <div className="ninja-screen__title-area">
          <p className="ninja-screen__title pap-pixel-title">손은 눈보다 빠르다</p>
          <p className="ninja-screen__round">
            ROUND <strong>{round}</strong> / {totalRounds}
            {exchange !== null && <span className="ninja-screen__exchange">{exchange}번째 교환</span>}
          </p>
        </div>
        {!isIntermission && roundTimerSeconds !== null && (
          <div className={`ninja-timer${roundTimerSeconds <= 5 ? ' ninja-timer--urgent' : ''}`}>
            <span className="ninja-timer__num pap-pixel-title">{roundTimerSeconds}</span>
            <span className="ninja-timer__unit">초</span>
          </div>
        )}
      </header>

      <div className="ninja-screen__body">
        <section className="ninja-screen__stage">
          <div className={`ninja-grid ninja-grid--${seats.length}`}>
            {seats.map((p, seat) => {
              const id = p.identity;
              const trackRef = trackByIdentity.get(id);
              const value = hp[id] ?? MAX_HP;
              const dead = !alivePlayers.includes(id);
              const isMe = id === myId;
              const attacker = currentAttackerToken === id;
              const judged = entries[id];
              // 공격권이 내게 있고 상대가 둘 이상일 때만 타일이 선택 버튼이 된다(한 명이면 자동 공격).
              const pickable =
                isMyAttack && !isMe && !dead && !isIntermission &&
                alivePlayers.filter((t) => t !== myId).length > 1;
              return (
                <div
                  key={id}
                  className={`ninja-tile ninja-tile--p${(seat % 4) + 1}${dead ? ' ninja-tile--dead' : ''}${
                    attacker ? ' ninja-tile--attacker' : ''
                  }`}
                >
                  <div className="ninja-tile__cam">
                    {trackRef ? (
                      <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
                    ) : (
                      <span className="ninja-tile__avatar pap-pixel-title">
                        {nicknameOf(id).slice(0, 1)}
                      </span>
                    )}
                    {/* 이펙트는 이 타일 안에서만 재생된다 — 컴포넌트가 호스트 div 크기에 맞춰 그린다 */}
                    {effectTargetId === id &&
                      (pixelEffect ?? (requiredSkill && <NinjaEffectOverlay effect={requiredSkill.effect} />))}
                    {isMe && <span className="ninja-tile__badge ninja-tile__badge--me">ME</span>}
                    {attacker && <span className="ninja-tile__badge ninja-tile__badge--attack">공격권</span>}
                    {dead && (
                      <div className="ninja-tile__dead">
                        <span className="pap-pixel-title">탈락</span>
                      </div>
                    )}
                    {/* 남이 지금 무슨 손동작을 잡고 있는지 — 예전엔 좌하단 박스에 UUID로 나왔다 */}
                    {judged?.comboLabel && !dead && (
                      <span className="ninja-tile__combo">{judged.comboLabel}</span>
                    )}
                    {pickable && (
                      <button
                        type="button"
                        className="ninja-tile__pick"
                        onClick={() => void submitTarget(id)}
                      >
                        <span className="pap-pixel-title">공격!</span>
                      </button>
                    )}
                  </div>

                  <div className="ninja-tile__bar">
                    <span className="ninja-tile__name">{nicknameOf(id)}</span>
                    <span className="ninja-tile__score pap-pixel-title">
                      {sessionTotals[id] ?? 0}점
                    </span>
                  </div>
                  <div className="ninja-hp">
                    <div
                      className={`ninja-hp__fill${value <= 30 ? ' ninja-hp__fill--low' : ''}`}
                      style={{ width: `${Math.max(0, Math.min(100, value))}%` }}
                    />
                    <span className="ninja-hp__num pap-pixel-title">{value}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <aside className="ninja-screen__sidebar">
          {!isIntermission && requiredSkill && (
            <div className="ninja-card ninja-card--skill">
              <p className="ninja-card__label">요구 스킬</p>
              <p className="ninja-card__skill-name pap-pixel-title">{requiredSkill.skillName}</p>
              <ol className="ninja-steps">
                {requiredSkill.gestures.map((step, index) => (
                  <li
                    key={step.seq}
                    className={`ninja-step${
                      index < stepIndex
                        ? ' ninja-step--done'
                        : index === stepIndex
                          ? ' ninja-step--active'
                          : ''
                    }`}
                  >
                    <span className="ninja-step__no pap-pixel-title">{index + 1}</span>
                    <span className="ninja-step__kr">{step.gestureLabelKr}</span>
                    {index === stepIndex && !completed && (
                      <span className="ninja-step__hold">
                        <span className="ninja-step__hold-fill" style={{ width: `${holdProgress * 100}%` }} />
                      </span>
                    )}
                  </li>
                ))}
              </ol>
              {completed && !attackAccepted && !currentAttackerToken && (
                <p className="ninja-card__flash">콤보 완성 — 전송 중</p>
              )}
              {attackAccepted && <p className="ninja-card__flash ninja-card__flash--win">공격권 획득!</p>}
              {currentAttackerToken && !isMyAttack && (
                <p className="ninja-card__flash ninja-card__flash--lost">
                  {nicknameOf(currentAttackerToken)} 님이 선점
                </p>
              )}
            </div>
          )}

          {isMyAttack && !isIntermission && attackTimerSeconds !== null && (
            <div className="ninja-card ninja-card--pick">
              <p className="ninja-card__label">대상 지정</p>
              <span className="ninja-card__pick-timer">
                <span style={{ width: `${(attackTimerSeconds / ATTACK_TARGET_TIMER_SECONDS) * 100}%` }} />
              </span>
              <p className="ninja-card__hint">
                {alivePlayers.filter((t) => t !== myId).length > 1
                  ? '캠에서 상대를 고르세요'
                  : '자동으로 공격합니다...'}
              </p>
            </div>
          )}

          {!isIntermission && requiredSkill?.nextSkill && (
            <div className="ninja-card ninja-card--next">
              <p className="ninja-card__label">다음 예고</p>
              <p className="ninja-card__next-name">{requiredSkill.nextSkill.skillName}</p>
              <p className="ninja-card__next-combo">
                {requiredSkill.nextSkill.gestures.map((g) => g.gestureLabelKr).join(' → ')}
              </p>
            </div>
          )}

          {isIntermission && (
            <div className="ninja-card ninja-card--intermission">
              {boutResult && boutResult.length > 0 ? (
                <>
                  <p className="ninja-card__label">ROUND {round} 결과</p>
                  <ol className="ninja-result">
                    {boutResult.map((entry) => (
                      <li key={entry.token} className={entry.token === myId ? 'ninja-result--me' : ''}>
                        <span className="ninja-result__rank pap-pixel-title">{entry.rank}</span>
                        <span className="ninja-result__name">{nicknameOf(entry.token)}</span>
                        <span className="ninja-result__pt pap-pixel-title">+{entry.points}</span>
                      </li>
                    ))}
                  </ol>
                </>
              ) : (
                <>
                  <p className="ninja-card__label">교환 종료</p>
                  {lastAttack && (
                    <p className="ninja-card__hit">
                      {nicknameOf(lastAttack.attackerToken)} → {nicknameOf(lastAttack.targetToken)}
                      <strong> -{lastAttack.damage}</strong>
                      {lastAttack.targetEliminated && <span className="ninja-card__ko">탈락!</span>}
                    </p>
                  )}
                </>
              )}
              {inCountdown && countdownSeconds !== null && (
                <p className="ninja-card__countdown pap-pixel-title">{countdownSeconds}</p>
              )}
            </div>
          )}

          {/* 손동작 인식 루프를 소유한 패널 — 내 손이 어떻게 잡히는지 보여야 하므로 항상 마운트한다 */}
          <GesturePanel />
        </aside>
      </div>

      {gameEnded && (
        <div className="ninja-final">
          <div className="ninja-final__card">
            <p className="ninja-final__title pap-pixel-title">최종 순위</p>
            <ol className="ninja-final__list">
              {ranking.map((entry) => (
                <li key={entry.token} className={entry.token === myId ? 'ninja-final--me' : ''}>
                  <span className="ninja-final__rank pap-pixel-title">{entry.rank}</span>
                  <span className="ninja-final__name">{nicknameOf(entry.token)}</span>
                  <span className="ninja-final__pt pap-pixel-title">{sessionTotals[entry.token] ?? 0}점</span>
                </li>
              ))}
            </ol>
          </div>
        </div>
      )}

      {error && <p className="ninja-screen__error">{error}</p>}
    </div>
  );
}
