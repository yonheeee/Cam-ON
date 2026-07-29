import {
  ParticipantTile,
  useDataChannel,
  useLocalParticipant,
  useParticipants,
  useTracks,
} from '@livekit/components-react';
import { Track } from 'livekit-client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../../gesture/lib/gestureBroadcast';
import { useGestureBoardStore } from '../../gesture/store/gestureBoardStore';
import { useNinjaRound } from '../hooks/useNinjaRound';
import { gestureImage } from '../lib/gestureImages';
import { skillEffect } from '../lib/skillEffects';
import { NinjaEffectOverlay } from './NinjaEffectOverlay';
import './NinjaBattleScreen.css';

const ATTACK_TARGET_TIMER_SECONDS = 30;
const MAX_HP = 100;

// 닌자 전투 화면. 대기방(LobbyScreen)/몸으로말해요(CharadesGamePanel)와 같은 방식으로 이 화면이
// 캠 타일까지 직접 그린다 — 그래서 HP·점수·공격권·이펙트를 "그 사람 타일 위"에 얹을 수 있다.
//
// 배치: 참가자 캠을 좌/우 열로 갈라 세우고 따라할 인술 카드를 가운데 둔다(시안 기준). 시선이
// 중앙 한 곳에 머무르면서 양옆 상대 상태가 주변시에 들어온다 — 손동작 게임이라 화면을 훑을
// 여유가 없다. 좌우 열은 각 30%, 중앙이 남은 40%를 갖는다(중앙 : 좌우합 ≈ 0.62 : 1, 황금비).
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
    roundResult,
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

  // 짝수 자리는 왼쪽, 홀수 자리는 오른쪽. 2인전은 1:1 대면, 3인은 2:1, 4인은 2:2가 된다.
  const leftSeats = seats.filter((_, i) => i % 2 === 0);
  const rightSeats = seats.filter((_, i) => i % 2 === 1);

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

  // 피격 진동 트리거. 같은 클래스를 다시 붙여도 CSS 애니메이션은 재생되지 않으므로 두 클래스를
  // 번갈아 붙여야 하는데, 그 기준을 교환 홀짝으로 삼으면 판이 짝수 교환에서 끝났을 때 다음 판
  // 첫 피격의 홀짝이 그대로여서 진동을 건너뛴다. 교환이 바뀔 때마다 1 늘어나는 카운터로 판단한다.
  const [shakeTick, setShakeTick] = useState(0);
  const shakeKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!inEffectPlayback) return;
    const key = `${round}:${exchange}`;
    if (shakeKeyRef.current === key) return;
    shakeKeyRef.current = key;
    setShakeTick((tick) => tick + 1);
  }, [inEffectPlayback, round, exchange]);

  if (!gameStarted) return null;

  // 이펙트는 맞은 사람 타일에서 재생한다 — 데미지가 "누구에게" 들어갔는지가 화면에서 바로 읽힌다.
  const effectTargetId = inEffectPlayback ? (lastAttack?.targetToken ?? null) : null;
  const pixelEffect = effectTargetId ? skillEffect(lastAttack?.skillId ?? requiredSkill?.skillId) : null;

  const renderTile = (id: string, seat: number) => {
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
            <span className="ninja-tile__avatar pap-pixel-title">{nicknameOf(id).slice(0, 1)}</span>
          )}
          {/* 내 손 스켈레톤은 내 캠 위에 직접 그린다 — 랜드마크는 내 카메라에서만 나오므로
              (남의 랜드마크는 전송되지 않는다) 각자 자기 타일에서만 보인다. 이 컴포넌트가
              인식 루프를 소유하므로 게임 중 정확히 한 번만 마운트된다. */}
          {isMe && <GesturePanel variant="overlay" />}
          {/* 이펙트는 이 타일 안에서만 재생된다 — 컴포넌트가 호스트 div 크기에 맞춰 그린다 */}
          {effectTargetId === id &&
            (pixelEffect ?? (requiredSkill && <NinjaEffectOverlay effect={requiredSkill.effect} />))}
          {/* 닉네임은 전원 같은 방식(픽셀 스티커)으로 캠 위에 얹는다 — 내 것만 노란색 */}
          <span className={`ninja-tile__badge ninja-tile__badge--name${isMe ? ' ninja-tile__badge--me' : ''}`}>
            {nicknameOf(id)}
          </span>
          {attacker && <span className="ninja-tile__badge ninja-tile__badge--attack">공격권</span>}
          {dead && (
            <div className="ninja-tile__dead">
              <span className="pap-pixel-title">탈락</span>
            </div>
          )}
          {/* 지금 무슨 손동작이 잡혔는지 — 판정 라벨(gesture.name)을 그 손모양 이미지로 보여준다 */}
          {judged?.comboLabel && !dead && (
            <span className="ninja-tile__combo">
              {gestureImage(judged.comboLabel) ? (
                <img
                  className="ninja-mini-seal"
                  src={gestureImage(judged.comboLabel)!}
                  alt={judged.comboLabel}
                  title={judged.comboLabel}
                />
              ) : (
                judged.comboLabel
              )}
            </span>
          )}
          {pickable && (
            <button type="button" className="ninja-tile__pick" onClick={() => void submitTarget(id)}>
              <span className="pap-pixel-title">공격!</span>
            </button>
          )}
        </div>

        <div className="ninja-hp">
          <div
            className={`ninja-hp__fill${value <= 30 ? ' ninja-hp__fill--low' : ''}`}
            style={{ width: `${Math.max(0, Math.min(100, value))}%` }}
          />
          <span className="ninja-hp__num pap-pixel-title">
            {value} / {MAX_HP}
          </span>
        </div>
      </div>
    );
  };

  // 공격자가 대상을 고르는 중인가 — 남은 상대가 둘 이상일 때만 실제로 "고르는" 순간이 있다
  // (한 명이면 useNinjaRound가 자동으로 공격한다). 고르는 본인에게는 띄우지 않는다.
  const someoneElsePicking =
    !isIntermission &&
    currentAttackerToken !== null &&
    currentAttackerToken !== myId &&
    alivePlayers.filter((t) => t !== currentAttackerToken).length > 1;

  return (
    <div className="ninja-screen">
      {/* 배경을 별도 레이어로 뺀 이유: 피격 진동을 이 레이어의 transform으로만 돌린다. 화면 전체를
          흔들면 캠 비디오·pixi 캔버스까지 매 프레임 재합성돼 무겁고, 영상이 같이 떨려서 어지럽다.
          이 레이어는 자식이 없어서 합성만 다시 하면 되고, scale로 살짝 키워둬서 흔들려도 여백이
          드러나지 않는다. -a/-b를 번갈아 붙이는 이유는 위 shakeTick 주석 참고. */}
      <div
        className={`ninja-screen__bg${
          inEffectPlayback ? (shakeTick % 2 ? ' ninja-screen__bg--shake-a' : ' ninja-screen__bg--shake-b') : ''
        }`}
      />
      <header className="ninja-screen__topbar">
        <img className="ninja-screen__logo pap-pixel-img" src="/assets/cam-on-logo.png" alt="CAM, ON!" />
      </header>

      <div className="ninja-screen__body">
        <section className="ninja-screen__col ninja-screen__col--left">
          {leftSeats.map((p) => renderTile(p.identity, seats.indexOf(p)))}
        </section>

        {/* 중앙 — 라운드/타이머 + 따라할 인술. 게임 중 시선이 머무는 곳이라 여기만 보면 된다. */}
        <section className="ninja-screen__center">
          <div className="ninja-board">
            <div className="ninja-board__head">
              <p className="ninja-board__round">
                ROUND <strong>{round}</strong> / {totalRounds}
              </p>
              <p
                className={`ninja-board__timer pap-pixel-title${
                  !isIntermission && roundTimerSeconds !== null && roundTimerSeconds <= 5
                    ? ' ninja-board__timer--urgent'
                    : ''
                }`}
              >
                {isIntermission
                  ? inCountdown && countdownSeconds !== null
                    ? `00:${String(countdownSeconds).padStart(2, '0')}`
                    : '--:--'
                  : `00:${String(roundTimerSeconds ?? 0).padStart(2, '0')}`}
              </p>
            </div>

            {isIntermission ? (
              <div className="ninja-board__body ninja-board__body--intermission">
                {roundResult && roundResult.length > 0 ? (
                  <>
                    <p className="ninja-board__label">ROUND {round} 결과</p>
                    <ol className="ninja-result">
                      {roundResult.map((entry) => (
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
                    <p className="ninja-board__label">교환 종료</p>
                    {lastAttack ? (
                      <p className="ninja-board__hit">
                        {nicknameOf(lastAttack.attackerToken)} → {nicknameOf(lastAttack.targetToken)}
                        <strong> -{lastAttack.damage}</strong>
                        {lastAttack.targetEliminated && <span className="ninja-board__ko">탈락!</span>}
                      </p>
                    ) : (
                      <p className="ninja-board__hint">정리 중...</p>
                    )}
                  </>
                )}
                {inCountdown && <p className="ninja-board__hint">다음 교환을 준비하세요</p>}
              </div>
            ) : (
              requiredSkill && (
                <div className="ninja-board__body">
                  <p className="ninja-board__label">현재 술법</p>
                  <p className="ninja-board__skill pap-pixel-title">{requiredSkill.skillName}</p>

                  <p className="ninja-board__label ninja-board__label--steps">필요한 인술</p>
                  <ol className="ninja-seals">
                    {requiredSkill.gestures.map((step, index) => (
                      <li
                        key={step.seq}
                        className={`ninja-seal${
                          index < stepIndex
                            ? ' ninja-seal--done'
                            : index === stepIndex
                              ? ' ninja-seal--active'
                              : ''
                        }`}
                      >
                        <span className="ninja-seal__ring">
                          {gestureImage(step.gestureName) ? (
                            <img
                              className="ninja-seal__img"
                              src={gestureImage(step.gestureName)!}
                              alt={step.gestureLabelKr}
                              title={step.gestureLabelKr}
                            />
                          ) : (
                            /* girl_V 등 이미지가 없는 손동작은 한글 라벨로 */
                            <span className="ninja-seal__kr">{step.gestureLabelKr}</span>
                          )}
                          {index === stepIndex && !completed && (
                            <svg className="ninja-seal__hold" viewBox="0 0 100 100" aria-hidden>
                              {/* 0.7초 유지 진행도를 링 둘레로 — 원형이라 막대보다 자리를 안 먹는다.
                                  둘레 = 2πr = 2π×46 ≈ 289 */}
                              <circle
                                className="ninja-seal__hold-track"
                                cx="50"
                                cy="50"
                                r="46"
                                pathLength="289"
                              />
                              <circle
                                className="ninja-seal__hold-fill"
                                cx="50"
                                cy="50"
                                r="46"
                                pathLength="289"
                                strokeDasharray="289"
                                strokeDashoffset={289 * (1 - holdProgress)}
                              />
                            </svg>
                          )}
                        </span>
                        {index < requiredSkill.gestures.length - 1 && (
                          <span className="ninja-seal__arrow" aria-hidden>
                            ›
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>

                  {completed && !attackAccepted && !currentAttackerToken && (
                    <p className="ninja-board__flash">콤보 완성 — 전송 중</p>
                  )}
                  {attackAccepted && (
                    <p className="ninja-board__flash ninja-board__flash--win">공격권 획득!</p>
                  )}
                  {currentAttackerToken && !isMyAttack && (
                    <p className="ninja-board__flash ninja-board__flash--lost">
                      {nicknameOf(currentAttackerToken)} 님이 선점
                    </p>
                  )}

                  {isMyAttack && attackTimerSeconds !== null && (
                    <div className="ninja-board__pick">
                      <span className="ninja-board__pick-bar">
                        <span
                          style={{ width: `${(attackTimerSeconds / ATTACK_TARGET_TIMER_SECONDS) * 100}%` }}
                        />
                      </span>
                      <p className="ninja-board__hint">
                        {alivePlayers.filter((t) => t !== myId).length > 1
                          ? '캠에서 상대를 고르세요'
                          : '자동으로 공격합니다...'}
                      </p>
                    </div>
                  )}

                  {requiredSkill.nextSkill && (
                    <div className="ninja-board__next">
                      <p>
                        다음 예고 · <strong>{requiredSkill.nextSkill.skillName}</strong>
                      </p>
                      <span className="ninja-board__next-seals">
                        {requiredSkill.nextSkill.gestures.map((g) => {
                          const src = gestureImage(g.gestureName);
                          return src ? (
                            <img
                              key={g.seq}
                              className="ninja-mini-seal"
                              src={src}
                              alt={g.gestureLabelKr}
                              title={g.gestureLabelKr}
                            />
                          ) : (
                            <em key={g.seq} className="ninja-mini-seal ninja-mini-seal--text">
                              {g.gestureLabelKr}
                            </em>
                          );
                        })}
                      </span>
                    </div>
                  )}
                </div>
              )
            )}
          </div>
        </section>

        <section className="ninja-screen__col ninja-screen__col--right">
          {rightSeats.map((p) => renderTile(p.identity, seats.indexOf(p)))}
        </section>
      </div>

      {/* 공격권을 놓친 쪽은 지금까지 화면에 아무 변화가 없어서 "왜 멈췄지" 싶었다 —
          화면을 어둡게 덮어 지금이 남의 차례라는 것과 누가 고르고 있는지를 알린다. */}
      {someoneElsePicking && (
        <div className="ninja-waiting">
          <p className="ninja-waiting__text">
            <strong>{nicknameOf(currentAttackerToken)}</strong> 님이 공격할 대상을 고르는 중입니다
            <span className="ninja-waiting__dots" aria-hidden>
              ...
            </span>
          </p>
        </div>
      )}

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
