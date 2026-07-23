import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ninjaApi, NinjaApiError, type RankingEntry, type RoundSkillResponse } from '../api/ninjaApi';
import { useSequenceProgress } from '../lib/sequenceProgress';

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
  participantId: string | null,
  comboLabel: string | null,
  comboConfidence: number,
) {
  const [round, setRound] = useState<number | null>(null);
  const [totalRounds, setTotalRounds] = useState<number | null>(null);
  const [alivePlayers, setAlivePlayers] = useState<string[]>([]);
  const [hp, setHp] = useState<Record<string, number>>({});
  const [currentAttackerToken, setCurrentAttackerToken] = useState<string | null>(null);
  const [ranking, setRanking] = useState<RankingEntry[]>([]);
  const [requiredSkill, setRequiredSkill] = useState<RoundSkillResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [seeding, setSeeding] = useState(false);

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
      const state = await ninjaApi.getState(participantId);
      if (!mountedRef.current) return;
      setRound(state.round || null);
      setTotalRounds(state.totalRounds || null);
      setAlivePlayers(state.alivePlayers);
      setHp(state.hp);
      setCurrentAttackerToken(state.currentAttackerToken);
      setRanking(state.ranking);
    } catch {
      // 세션이 아직 없으면 404 — 조용히 무시하고 다음 폴링을 기다린다.
    }
  }, [participantId]);

  useEffect(() => {
    if (!participantId) return;
    poll();
    const interval = setInterval(poll, STATE_POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [participantId, poll]);

  useEffect(() => {
    if (!participantId || round == null) {
      setRequiredSkill(null);
      return;
    }
    let cancelled = false;
    ninjaApi
      .getRoundSkill(round, participantId)
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
  }, [participantId, round]);

  // 시퀀스 완성 시 공격 제출 — 같은 라운드에 두 번 쏘지 않도록 마지막으로 제출한 라운드를 기억.
  const attackedRoundRef = useRef<number | null>(null);
  useEffect(() => {
    if (!completed || !participantId || !requiredSkill || round == null) return;
    if (attackedRoundRef.current === round) return;
    attackedRoundRef.current = round;

    ninjaApi
      .submitAttack(round, requiredSkill.skillId, participantId)
      .then(() => poll())
      .catch((err: unknown) => {
        // 이미 다른 참가자가 선점(NINJA_ALREADY_CLAIMED)한 것도 정상적인 결과라 에러로만 표시.
        setError(err instanceof NinjaApiError ? err.message : '공격 제출 실패');
      });
  }, [completed, participantId, requiredSkill, round, poll]);

  const submitTarget = useCallback(
    async (targetToken: string) => {
      if (!participantId || round == null) return;
      try {
        await ninjaApi.submitTarget(round, targetToken, participantId);
        await poll(); // 다음 폴링까지 안 기다리고 HP/라운드 전환을 바로 반영
      } catch (err) {
        setError(err instanceof NinjaApiError ? err.message : '대상 지정 실패');
      }
    },
    [participantId, round, poll],
  );

  // 공격권을 획득했는데 생존한 상대가 정확히 한 명이면(2인전 등) 굳이 고를 필요가 없어서 자동으로
  // 그 상대를 공격한다 — 상대가 둘 이상이면 진짜 전략적 선택이라 수동 지정을 그대로 둔다.
  const targetedRoundRef = useRef<number | null>(null);
  useEffect(() => {
    if (!participantId || round == null || currentAttackerToken !== participantId) return;
    if (targetedRoundRef.current === round) return;
    const others = alivePlayers.filter((token) => token !== participantId);
    if (others.length !== 1) return;
    targetedRoundRef.current = round;
    void submitTarget(others[0]);
  }, [participantId, round, currentAttackerToken, alivePlayers, submitTarget]);

  const seed = useCallback(
    async (participantTokens: string[], totalRoundsInput: number) => {
      setSeeding(true);
      setError(null);
      try {
        await ninjaApi.seed(participantTokens, totalRoundsInput);
        // 새 게임도 라운드 번호가 1부터 다시 시작되는데, 이 ref들이 이전 판 값(예: 1)을 그대로
        // 들고 있으면 "같은 라운드 번호엔 한 번만 제출"이라는 중복 방지 로직이 새 판의 그 라운드를
        // 스킵해버린다 — 리셋해야 새 판에서도 정상적으로 공격/대상 지정이 제출된다.
        attackedRoundRef.current = null;
        targetedRoundRef.current = null;
        await poll();
      } catch (err) {
        setError(err instanceof NinjaApiError ? err.message : '게임 시작 실패');
      } finally {
        setSeeding(false);
      }
    },
    [poll],
  );

  const [resetting, setResetting] = useState(false);
  const resetGame = useCallback(async () => {
    setResetting(true);
    setError(null);
    try {
      await ninjaApi.reset();
      attackedRoundRef.current = null;
      targetedRoundRef.current = null;
      await poll();
    } catch (err) {
      setError(err instanceof NinjaApiError ? err.message : '게임 초기화 실패');
    } finally {
      setResetting(false);
    }
  }, [poll]);

  const isMyAttack = participantId !== null && currentAttackerToken === participantId;
  const gameEnded = ranking.length > 0;

  // 라운드 제한시간(15초) 카운트다운. 서버 데드라인을 못 받아서(폴링 기반) round가 바뀔 때마다
  // 클라이언트가 15초부터 다시 센다 — 누군가 공격권을 획득하는 순간(currentAttackerToken이 채워짐)
  // 승부는 이미 난 거라 0으로 확정해서 "타이머가 멈췄다"는 걸 보여준다.
  const [roundTimerSeconds, setRoundTimerSeconds] = useState<number | null>(null);
  useEffect(() => {
    if (round == null) {
      setRoundTimerSeconds(null);
      return;
    }
    setRoundTimerSeconds(ROUND_DURATION_SECONDS);
    const interval = setInterval(() => {
      setRoundTimerSeconds((prev) => (prev !== null && prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(interval);
  }, [round]);

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
  }, [isMyAttack, round]);

  return {
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
    resetSequence: reset,
  };
}
