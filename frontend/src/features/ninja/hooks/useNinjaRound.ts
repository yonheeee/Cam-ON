import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ninjaApi,
  NinjaApiError,
  type LastAttack,
  type NinjaPhase,
  type RankingEntry,
  type RoundSkillResponse,
} from '../api/ninjaApi';
import { useSequenceProgress } from '../lib/sequenceProgress';
import { useNinjaRealtime } from './useNinjaRealtime';

const STATE_POLL_INTERVAL_MS = 1500;
const ATTACK_TARGET_TIMER_SECONDS = 30;
// 백엔드 NinjaGameService.ROUND_DURATION(30초)과 맞춤 — 서버가 실제 데드라인을 안 내려주기 때문에
// (폴링 기반이라 WS RoundStartedPayload.deadline을 못 받음) 라운드 번호가 바뀔 때마다 클라이언트가
// 자체적으로 다시 세는 근사치다. 폴링 텀(1.5초)만큼 서버 시각과 어긋날 수 있지만 UI 용도로는 충분.
const ROUND_DURATION_SECONDS = 30;

// 원래 라운드 시작/공격 결과 전파는 STOMP(/ws/rooms/{roomId})로 와야 하는데 방/세션 도메인이
// 아직 없어서 GET .../state를 폴링한다. 상태 모양(반환 타입)은 그대로 두고 나중에 STOMP
// 구독으로 갈아끼우면 되도록, 이 훅 밖(NinjaGamePanel)에는 폴링 여부가 안 드러나게 했다.
export function useNinjaRound(
  roomId: string,
  gameId: number,
  accessToken: string,
  participantId: string | null,
  comboLabel: string | null,
  comboConfidence: number,
) {
  const [round, setRound] = useState<number | null>(null);
  // round=판(bout), exchange=판 안의 교환. 판이 이어지는 동안 exchange가 늘고, 판이 바뀌면 1로 리셋된다.
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

  // requiredSkill이 실제로 바뀔 때만(=라운드 전환) 새 배열이 되도록 메모.
  // 그냥 매 렌더 .map()을 새로 만들면 참조가 매번 달라져서, useSequenceProgress의
  // "시퀀스가 바뀌면 초기화" 이펙트가 폴링/콤보 갱신 때마다 오작동해 진행도가 0.7초를
  // 못 채우고 계속 리셋되는 버그가 있었다.
  const requiredSequence = useMemo(
    () => requiredSkill?.gestures.map((g) => g.gestureName) ?? null,
    [requiredSkill],
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

  // attack/target 제출 직후에도 즉시 다시 부를 수 있도록 poll 자체를 재사용 가능한 함수로 뺐다 —
  // 안 그러면 최대 폴링 주기(1.5초)만큼 화면이 "안 바뀌는 것처럼" 느껴진다.
  const poll = useCallback(async () => {
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
    } catch {
      // 세션이 아직 없으면 404 — 조용히 무시하고 다음 폴링을 기다린다.
    }
  }, [participantId, gameId, accessToken]);

  // ninja:* WS 이벤트가 오면 폴링 주기를 기다리지 않고 즉시 다시 읽어 반영한다(실시간성). 상태의
  // 단일 소스는 여전히 getState — 이 구독은 "언제 읽을지"만 앞당긴다(WS 놓쳐도 폴링 폴백).
  useNinjaRealtime(
    roomId,
    accessToken,
    useCallback(() => {
      void poll();
    }, [poll]),
  );

  useEffect(() => {
    if (!participantId) return;
    poll();
    const interval = setInterval(poll, STATE_POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [participantId, poll]);

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

  // 시퀀스 완성 시 공격 제출 — 같은 "교환"에 두 번 쏘지 않도록 마지막으로 제출한 (판,교환)을 기억한다.
  // (라운드만 키로 쓰면 한 판 안의 두 번째 교환부터 제출이 막힌다.)
  const attackedKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!completed || !participantId || !requiredSkill || round == null || exchange == null) return;
    // 인터미션(이펙트/카운트다운) 중엔 입력을 받지 않는다 — 다음 교환이 서버에서 열리기 전까진
    // 공격 제출 자체가 무의미하고(교환 이미 닫힘), 선입력으로 이어질 수 있다.
    if (phase !== 'ROUND') return;
    const key = `${round}:${exchange}`;
    if (attackedKeyRef.current === key) return;
    attackedKeyRef.current = key;

    ninjaApi
      .submitAttack(gameId, round, requiredSkill.skillId, accessToken)
      .then(() => poll())
      .catch((err: unknown) => {
        // 이미 다른 참가자가 선점(NINJA_ALREADY_CLAIMED)한 것도 정상적인 결과라 에러로만 표시.
        setError(err instanceof NinjaApiError ? err.message : '공격 제출 실패');
      });
  }, [completed, participantId, requiredSkill, round, exchange, phase, poll, gameId, accessToken]);

  const submitTarget = useCallback(
    async (targetToken: string) => {
      if (!participantId || round == null) return;
      try {
        await ninjaApi.submitTarget(gameId, round, targetToken, accessToken);
        await poll(); // 다음 폴링까지 안 기다리고 HP/라운드 전환을 바로 반영
      } catch (err) {
        setError(err instanceof NinjaApiError ? err.message : '대상 지정 실패');
      }
    },
    [participantId, round, poll, gameId, accessToken],
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

  const [resetting, setResetting] = useState(false);
  const resetGame = useCallback(async () => {
    setResetting(true);
    setError(null);
    try {
      await ninjaApi.reset(roomId, accessToken);
      attackedKeyRef.current = null;
      targetedKeyRef.current = null;
      await poll();
    } catch (err) {
      setError(err instanceof NinjaApiError ? err.message : '게임 초기화 실패');
    } finally {
      setResetting(false);
    }
  }, [poll, roomId, accessToken]);

  const isMyAttack = participantId !== null && currentAttackerToken === participantId;
  const gameEnded = ranking.length > 0;

  // 라운드 제한시간(15초) 카운트다운. 서버 데드라인을 못 받아서(폴링 기반) round가 바뀔 때마다
  // 클라이언트가 15초부터 다시 센다 — 누군가 공격권을 획득하는 순간(currentAttackerToken이 채워짐)
  // 승부는 이미 난 거라 0으로 확정해서 "타이머가 멈췄다"는 걸 보여준다.
  const [roundTimerSeconds, setRoundTimerSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (round == null || exchange == null) {
      setRoundTimerSeconds(null);
      return;
    }
    setRoundTimerSeconds(ROUND_DURATION_SECONDS);
    const interval = setInterval(() => {
      setRoundTimerSeconds((prev) => (prev !== null && prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [round, exchange]);

  useEffect(() => {
    if (currentAttackerToken) setRoundTimerSeconds(0);
  }, [currentAttackerToken]);

  // 공격권을 획득하면 대상을 지정할 30초를 보여준다 — 백엔드가 이 창을 별도로 강제하진 않고
  // (라운드 자체 타임아웃만 서버가 관리) 순수 UI 재촉용 타이머다.
  const [attackTimerSeconds, setAttackTimerSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (!isMyAttack) {
      setAttackTimerSeconds(null);
      return;
    }
    setAttackTimerSeconds(ATTACK_TARGET_TIMER_SECONDS);
    const interval = setInterval(() => {
      setAttackTimerSeconds((prev) => (prev !== null && prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [isMyAttack, round, exchange]);

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
    resetSequence: reset,
    // 서버 주도 인터미션
    phase,
    isIntermission,
    inEffectPlayback,
    inCountdown,
    countdownSeconds,
    lastAttack,
  };
}
