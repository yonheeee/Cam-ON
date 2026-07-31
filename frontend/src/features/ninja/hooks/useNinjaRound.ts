import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ninjaApi,
  NinjaApiError,
  type NinjaAttackResolvedEvent,
  type NinjaAttackWonEvent,
  type NinjaGameEndedEvent,
  type NinjaRoundStartedEvent,
  type NinjaRoundTimeoutEvent,
  type RoundResultEntry,
  type LastAttack,
  type NinjaPhase,
  type RankingEntry,
  type RoundSkillResponse,
} from '../api/ninjaApi';
import { useSequenceProgress } from '../lib/sequenceProgress';
import { useNinjaRealtime } from './useNinjaRealtime';

// 대상 지정 제한시간 폴백 — 서버 데드라인(attack-won의 targetDeadlineAt)을 못 받은 경우에만.
// 백엔드 NinjaGameService.TARGET_DURATION(15초)과 맞춤.
const ATTACK_TARGET_TIMER_SECONDS = 15;
// 교환 제한시간 폴백 — 서버 데드라인(ninja:round-started의 deadlineAt)이 있으면 그걸 쓰고,
// 이벤트를 못 받은 경우(새로고침 직후 스냅샷 동기화로 진입)에만 이 근사치로 다시 센다.
// 백엔드 NinjaGameService.EXCHANGE_DURATION(30초)과 맞춤.
const ROUND_DURATION_SECONDS = 30;

// 상태 동기화 구조("상태 변경 요청은 REST, 변경 전파는 WS" 프로젝트 원칙):
// - 입장/STOMP (재)연결 시 GET .../state로 전체 스냅샷을 1회 동기화하고,
// - 이후에는 서버가 미는 ninja:* 이벤트로만 증분 갱신한다. 폴링 없음.
// 이벤트는 접속 중인 사람에게만 가므로, 새로고침/끊김 복귀의 이벤트 공백은 재연결 시점의
// 스냅샷 동기화가 메운다(useNinjaRealtime.onConnected).
export function useNinjaRound(
  roomId: string,
  gameId: number,
  accessToken: string,
  participantId: string | null,
  comboLabel: string | null,
  comboConfidence: number,
) {
  const [round, setRound] = useState<number | null>(null);
  // round=판(round), exchange=판 안의 교환. 판이 이어지는 동안 exchange가 늘고, 판이 바뀌면 1로 리셋된다.
  const [exchange, setExchange] = useState<number | null>(null);
  const [totalRounds, setTotalRounds] = useState<number | null>(null);
  const [alivePlayers, setAlivePlayers] = useState<string[]>([]);
  const [hp, setHp] = useState<Record<string, number>>({});
  const [currentAttackerToken, setCurrentAttackerToken] = useState<string | null>(null);
  const [ranking, setRanking] = useState<RankingEntry[]>([]);
  const [requiredSkill, setRequiredSkill] = useState<RoundSkillResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 서버 주도 인터미션 상태. 진행/전환은 이 값들(특히 서버 기준 시각)로만 판단한다.
  const [phase, setPhase] = useState<NinjaPhase | null>(null);
  const [effectUntil, setEffectUntil] = useState<number | null>(null);
  const [nextRoundAt, setNextRoundAt] = useState<number | null>(null);
  const [lastAttack, setLastAttack] = useState<LastAttack | null>(null);
  // 판을 가로질러 누적된 참가자별 점수(최종 발표 합산). 게임 진행 중에도 실시간 노출.
  const [sessionTotals, setSessionTotals] = useState<Record<string, number>>({});
  // 공격 제출이 "서버에서 수락된"(=공격권 선점 성공, 200) 교환 키. 클라의 콤보 완성이 아니라 이 값으로
  // "공격 성공" 표시를 판단한다 — 콤보를 완성해도 남이 먼저 선점했으면 여기엔 안 들어온다.
  const [attackAckKey, setAttackAckKey] = useState<string | null>(null);
  // 방금 끝난 판의 순위+획득 점수(판 종료 인터미션 동안만 채워짐).
  const [roundResult, setRoundResult] = useState<RoundResultEntry[] | null>(null);
  // 현재 교환의 서버 데드라인(ninja:round-started의 deadlineAt). 스냅샷 동기화(GET .../state)에는
  // 데드라인이 없어서 새로고침 직후엔 null일 수 있다 — 그 경우 타이머는 30초 근사치로 폴백.
  const [roundDeadline, setRoundDeadline] = useState<number | null>(null);
  // 공격권 획득 시 서버가 내려주는 대상 지정 데드라인(attack-won의 targetDeadlineAt).
  const [targetDeadline, setTargetDeadline] = useState<number | null>(null);

  // 이 판에서 내가 탈락했는가. alivePlayers가 비어 있는 동안(스냅샷 도착 전)은 판단하지 않는다 —
  // 안 그러면 입장 직후 전원이 탈락으로 보인다.
  const isEliminated =
    participantId !== null && alivePlayers.length > 0 && !alivePlayers.includes(participantId);

  // requiredSkill이 실제로 바뀔 때만(=라운드 전환) 새 배열이 되도록 메모.
  // 그냥 매 렌더 .map()을 새로 만들면 참조가 매번 달라져서, useSequenceProgress의
  // "시퀀스가 바뀌면 초기화" 이펙트가 폴링/콤보 갱신 때마다 오작동해 진행도가 0.7초를
  // 못 채우고 계속 리셋되는 버그가 있었다.
  //
  // 탈락하면 시퀀스를 null로 내려 콤보 추적 자체를 멈춘다 — 탈락자가 손동작을 해도 판정되지
  // 않아야 한다. 서버도 NINJA_NOT_ALIVE로 거부하지만(그게 상태의 최종 방어선이다) 그것만
  // 믿으면 탈락자 화면에서 콤보가 완성되고 제출까지 갔다가 에러만 뜬다.
  const requiredSequence = useMemo(
    () =>
      isEliminated ? null : (requiredSkill?.gestures.map((g) => g.gestureName) ?? null),
    [isEliminated, requiredSkill],
  );
  const { stepIndex, completed, holdProgress, reset } = useSequenceProgress(
    requiredSequence,
    comboLabel,
    comboConfidence,
  );

  // StrictMode(dev)는 마운트를 setup→cleanup→setup 순서로 한 번 더 시뮬레이션하는데, setup에서
  // true로 되돌리는 코드 없이 cleanup에서만 false로 두면 그 시뮬레이션 이후 mountedRef.current가
  // 영원히 false로 남아버린다 — poll()이 fetch는 성공해도(백엔드 로그엔 200으로 찍힘) 매번
  // "언마운트됨"으로 착각해서 setState를 전부 건너뛰는 버그가 있었다. setup에서 명시적으로
  // true로 되돌려야 두 번째 setup 이후 정상 동작한다.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // 전체 스냅샷 동기화 — 입장/STOMP (재)연결 시에만 부른다. 진행 중 갱신은 전부 이벤트 리듀서 몫.
  const syncState = useCallback(async () => {
    if (!participantId) return;
    try {
      const state = await ninjaApi.getState(gameId, accessToken);
      if (!mountedRef.current) return;
      setRound(state.round || null);
      setExchange(state.exchange || null);
      setTotalRounds(state.totalRounds || null);
      setAlivePlayers(state.alivePlayers);
      setHp(state.hp);
      setCurrentAttackerToken(state.currentAttackerToken);
      setRanking(state.ranking);
      setPhase(state.phase);
      setEffectUntil(state.effectUntil ? Date.parse(state.effectUntil) : null);
      setNextRoundAt(state.nextRoundAt ? Date.parse(state.nextRoundAt) : null);
      setLastAttack(state.lastAttack);
      setSessionTotals(state.sessionTotals ?? {});
      setRoundResult(state.roundResult ?? null);
    } catch {
      // 세션이 아직 없으면 404 — 세션이 열리면 ninja:round-started 이벤트가 상태를 채운다.
    }
  }, [participantId, gameId, accessToken]);

  // ninja:* 이벤트 리듀서 — 각 이벤트 payload가 화면 상태를 직접 갱신한다(재조회 없음).
  const handleNinjaEvent = useCallback((eventName: string, data: unknown) => {
    if (!mountedRef.current) return;
    switch (eventName) {
      case 'ninja:round-started': {
        // 새 교환(판이 바뀌면 전원 부활/HP 리셋 스냅샷 포함) — 이전 인터미션 상태를 걷어낸다.
        const e = data as NinjaRoundStartedEvent;
        setRound(e.round);
        setExchange(e.exchange);
        setPhase('ROUND');
        setAlivePlayers(e.alivePlayers);
        setHp(e.hp);
        setCurrentAttackerToken(null);
        setLastAttack(null);
        setRoundResult(null);
        setEffectUntil(null);
        setNextRoundAt(null);
        setRoundDeadline(Date.parse(e.deadlineAt));
        setTargetDeadline(null); // 이전 교환의 대상 지정 창 잔재 제거
        break;
      }
      case 'ninja:attack-won': {
        const e = data as NinjaAttackWonEvent;
        setCurrentAttackerToken(e.attackerToken);
        // 공격권 획득 = 교환 30초 타이머가 멈추고 대상 지정 창이 열린다(서버 기준 시각).
        setTargetDeadline(e.targetDeadlineAt ? Date.parse(e.targetDeadlineAt) : null);
        break;
      }
      case 'ninja:attack-resolved': {
        const e = data as NinjaAttackResolvedEvent;
        setPhase(e.phase);
        setEffectUntil(e.effectUntil ? Date.parse(e.effectUntil) : null);
        setNextRoundAt(e.nextRoundAt ? Date.parse(e.nextRoundAt) : null);
        setHp((prev) => ({ ...prev, [e.targetToken]: e.targetHpAfter }));
        if (e.targetEliminated) {
          setAlivePlayers((prev) => prev.filter((token) => token !== e.targetToken));
        }
        setLastAttack({
          attackerToken: e.attackerToken,
          targetToken: e.targetToken,
          skillId: e.skillId,
          damage: e.damage,
          targetHpAfter: e.targetHpAfter,
          targetEliminated: e.targetEliminated,
        });
        // 판이 끝난 공격이면 결과창 재료(그 판의 순위/누적 점수)가 함께 실려 온다.
        if (e.roundResult) setRoundResult(e.roundResult);
        if (e.sessionTotals) setSessionTotals(e.sessionTotals);
        break;
      }
      case 'ninja:round-timeout': {
        // 아무도 콤보를 못 완성한 교환 — 서버가 생존자 전원 HP를 감쇠시키고 넘어간다.
        // 감쇠 반영 후 스냅샷(alivePlayers/hp)으로 화면 HP를 즉시 맞춘다 — 안 맞추면 다음
        // 판 시작까지 화면 HP가 서버와 어긋난다.
        const e = data as NinjaRoundTimeoutEvent;
        setPhase(e.phase);
        setEffectUntil(null);
        setNextRoundAt(e.nextRoundAt ? Date.parse(e.nextRoundAt) : null);
        setLastAttack(null);
        if (e.alivePlayers) setAlivePlayers(e.alivePlayers);
        if (e.hp) setHp(e.hp);
        if (e.roundResult) setRoundResult(e.roundResult);
        if (e.sessionTotals) setSessionTotals(e.sessionTotals);
        break;
      }
      case 'ninja:game-ended': {
        const e = data as NinjaGameEndedEvent;
        setRanking(e.ranking);
        setSessionTotals(e.sessionTotals);
        setPhase('ENDED');
        break;
      }
    }
  }, []);

  useNinjaRealtime(roomId, accessToken, handleNinjaEvent, syncState);

  // 마운트 직후 1회 동기화 — STOMP 연결이 늦거나 실패해도 최소한 현재 스냅샷은 그린다.
  // (재연결 동기화는 useNinjaRealtime.onConnected가 담당)
  useEffect(() => {
    if (!participantId) return;
    void syncState();
  }, [participantId, syncState]);

  // 판이 이어지는 동안 교환마다 요구 스킬이 바뀌므로, (round, exchange)가 바뀔 때마다 다시 조회한다
  // (서버는 "현재 교환"의 스킬을 돌려준다).
  useEffect(() => {
    if (!participantId || round == null || exchange == null) {
      setRequiredSkill(null);
      return;
    }
    let cancelled = false;
    ninjaApi
      .getRoundSkill(gameId, round, accessToken)
      .then((skill) => {
        if (!cancelled) setRequiredSkill(skill);
      })
      .catch((err: unknown) => {
        // 이게 조용히 실패하면 "요구 스킬"이 계속 null이라 콤보 진행 추적 자체가 시작을 안 해서,
        // 화면엔 아무 이유 없이 "안 되는" 것처럼 보인다 — 반드시 눈에 보이게 남긴다.
        if (cancelled) return;
        setRequiredSkill(null);
        setError(err instanceof NinjaApiError ? `요구 스킬 조회 실패: ${err.message}` : '요구 스킬 조회 실패');
      });
    return () => {
      cancelled = true;
    };
  }, [participantId, round, exchange, gameId, accessToken]);

  // 콤보 완성 → 공격 제출.
  // 함정: 교환이 넘어가도 이전 교환의 completed=true가 한 렌더 남아있다(useSequenceProgress의 시퀀스
  // 리셋이 이펙트라 한 박자 늦음). 그 stale 완성을 새 교환의 완성으로 오인하면 안 된다. 그래서 "이 교환에서
  // completed=false를 한 번이라도 본" 뒤(=그 교환의 스킬로 시퀀스가 리셋된 뒤)에 올라온 완성만 진짜로 친다(armed).
  // 이러면 전환 직후 stale 완성은 무시되고, 진짜 완성은 절대 놓치지 않는다. 같은 교환 중복 제출은 (판:교환) 키로 방지.
  const attackedKeyRef = useRef<string | null>(null);
  const armedKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (round == null || exchange == null) return;
    const key = `${round}:${exchange}`;
    if (!completed) {
      // 이 교환의 콤보를 아직 완성 안 한 상태를 봤다 → 이제부터의 완성은 이 교환의 진짜 완성이다.
      armedKeyRef.current = key;
      return;
    }
    if (armedKeyRef.current !== key) return; // 전환 직후 넘어온 이전 교환의 stale 완성 → 무시.
    // 인터미션(이펙트/카운트다운) 중엔 입력을 받지 않는다(선입력 방지).
    if (phase !== 'ROUND' || !participantId || !requiredSkill) return;
    // 탈락자는 제출하지 않는다. 위에서 시퀀스를 끊어 completed가 뜰 일이 없지만, 탈락 직전에
    // 완성해 둔 stale 완성이 남아 있을 수 있어 제출 지점에서 한 번 더 막는다.
    if (isEliminated) return;
    if (attackedKeyRef.current === key) return;
    attackedKeyRef.current = key;

    ninjaApi
      .submitAttack(gameId, round, requiredSkill.skillId, accessToken)
      .then(() => {
        // 서버가 공격권 선점을 수락(200)한 교환만 "공격 성공"으로 표시한다.
        // 화면 반영은 서버가 곧바로 쏘는 ninja:attack-won 이벤트가 담당한다(재조회 불필요).
        setAttackAckKey(key);
      })
      .catch((err: unknown) => {
        // 이미 다른 참가자가 선점(NINJA_ALREADY_CLAIMED)한 것도 정상적인 결과라 에러로만 표시.
        setError(err instanceof NinjaApiError ? err.message : '공격 제출 실패');
      });
  }, [
    completed,
    participantId,
    requiredSkill,
    round,
    exchange,
    phase,
    isEliminated,
    gameId,
    accessToken,
  ]);

  const submitTarget = useCallback(
    async (targetToken: string) => {
      if (!participantId || round == null) return;
      try {
        // HP/인터미션 전환 반영은 서버가 곧바로 쏘는 ninja:attack-resolved 이벤트가 담당한다.
        await ninjaApi.submitTarget(gameId, round, targetToken, accessToken);
      } catch (err) {
        setError(err instanceof NinjaApiError ? err.message : '대상 지정 실패');
      }
    },
    [participantId, round, gameId, accessToken],
  );

  // 공격권을 획득했는데 생존한 상대가 정확히 한 명이면(2인전 등) 굳이 고를 필요가 없어서 자동으로
  // 그 상대를 공격한다 — 상대가 둘 이상이면 진짜 전략적 선택이라 수동 지정을 그대로 둔다.
  const targetedKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!participantId || round == null || exchange == null || currentAttackerToken !== participantId) return;
    if (phase !== 'ROUND') return; // 인터미션에 접어들면 이미 대상 지정이 끝난 상태라 재시도하지 않는다.
    const key = `${round}:${exchange}`;
    if (targetedKeyRef.current === key) return;
    const others = alivePlayers.filter((token) => token !== participantId);
    if (others.length !== 1) return;
    targetedKeyRef.current = key;
    void submitTarget(others[0]);
  }, [participantId, round, exchange, currentAttackerToken, phase, alivePlayers, submitTarget]);


  const isMyAttack = participantId !== null && currentAttackerToken === participantId;
  // 현재 교환에서 내 공격 제출이 서버에 수락됐는가(콤보 완성이 아니라 서버 200 기준).
  const attackAccepted =
    round != null && exchange != null && attackAckKey === `${round}:${exchange}`;
  const gameEnded = ranking.length > 0;

  // 교환 제한시간 카운트다운. 서버 데드라인(deadlineAt)이 있으면 그 절대 시각까지 남은 시간으로
  // 시작하고(전원 동일한 값), 없으면(새로고침 직후 스냅샷 진입) 30초 근사치로 폴백한다.
  // 누군가 공격권을 획득하는 순간(currentAttackerToken이 채워짐) 승부는 이미 난 거라 0으로
  // 확정해서 "타이머가 멈췄다"는 걸 보여준다.
  const [roundTimerSeconds, setRoundTimerSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (round == null || exchange == null) {
      setRoundTimerSeconds(null);
      return;
    }
    const initial = roundDeadline !== null
      ? Math.max(0, Math.ceil((roundDeadline - Date.now()) / 1000))
      : ROUND_DURATION_SECONDS;
    setRoundTimerSeconds(initial);
    const interval = setInterval(() => {
      setRoundTimerSeconds((prev) => (prev !== null && prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [round, exchange, roundDeadline]);

  useEffect(() => {
    if (currentAttackerToken) setRoundTimerSeconds(0);
  }, [currentAttackerToken]);

  // 공격권 획득 후 대상 지정 카운트다운 — 서버가 attack-won에 실어준 데드라인(15초) 기준.
  // 시간 내 대상을 안 고르면 서버가 랜덤 자동 공격하므로, 이 타이머는 정확한 서버 시각을 보여줘야
  // "0초인데 안 넘어감/시간 남았는데 잘림" 같은 어긋남이 없다. 데드라인을 못 받은 경우(재접속 등)만
  // 근사치로 폴백.
  const [attackTimerSeconds, setAttackTimerSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (!isMyAttack) {
      setAttackTimerSeconds(null);
      return;
    }
    const initial = targetDeadline !== null
      ? Math.max(0, Math.ceil((targetDeadline - Date.now()) / 1000))
      : ATTACK_TARGET_TIMER_SECONDS;
    setAttackTimerSeconds(initial);
    const interval = setInterval(() => {
      setAttackTimerSeconds((prev) => (prev !== null && prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [isMyAttack, round, exchange, targetDeadline]);

  // 인터미션 진행은 서버 기준 시각으로만 판단한다 — 클라 로컬 카운터로 "몇 초 지났나"를 세지 않고,
  // effectUntil/nextRoundAt(절대 시각)까지 남은 시간을 매 틱 계산한다. 그래서 늦게 폴링한 클라이언트나
  // 재접속자도 같은 순간에 같은 화면(이펙트 → 카운트다운 → 다음 라운드)으로 수렴한다. (동일 LAN 데모
  // 전제로 기기 간 시계 오차는 무시할 수준으로 본다 — 서버 데드라인을 쓰는 기존 코드와 동일 가정.)
  const [now, setNow] = useState<number>(() => Date.now());
  const isIntermission = phase === 'INTERMISSION';
  useEffect(() => {
    if (!isIntermission) return;
    setNow(Date.now());
    const interval = setInterval(() => setNow(Date.now()), 200);
    return () => clearInterval(interval);
  }, [isIntermission, effectUntil, nextRoundAt]);

  const inEffectPlayback = isIntermission && effectUntil != null && now < effectUntil;
  const inCountdown =
    isIntermission &&
    nextRoundAt != null &&
    (effectUntil == null || now >= effectUntil) &&
    now < nextRoundAt;
  const countdownSeconds = inCountdown ? Math.max(1, Math.ceil((nextRoundAt - now) / 1000)) : null;

  return {
    round,
    exchange,
    sessionTotals,
    totalRounds,
    alivePlayers,
    hp,
    currentAttackerToken,
    isMyAttack,
    /** 이 판에서 내가 탈락했는가. true면 손동작 인식 결과를 판정에 쓰지 않는다(관전) */
    isEliminated,
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
    resetSequence: reset,
    // 서버 주도 인터미션
    phase,
    isIntermission,
    inEffectPlayback,
    inCountdown,
    countdownSeconds,
    lastAttack,
    roundResult,
  };
}
