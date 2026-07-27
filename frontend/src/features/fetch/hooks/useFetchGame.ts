import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { useCallback, useRef, useState } from 'react';
import { aiApi } from '../api/aiApi';

// 물건 가져오기 게임의 방-단위 진행 상태를 LiveKit 데이터 채널로 동기화하는 훅.
//
// ⚠ 임시(mock) 구조: 원래 설계에서 제시어 배포/제출 순서 판정은 Spring 몫인데
// (game:round-started WS, POST .../submissions), 해당 백엔드 도메인이 아직 없어서
// 방장이 제시어를 뽑아 브로드캐스트하고 성공 순서도 데이터 채널 도착 순서로 근사한다.
// 채팅(chat)·제스처(gesture-result)와 동일한 패턴. Spring API가 생기면 이 훅 내부만 교체.
const FETCH_TOPIC = 'fetch-game';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

// 30초는 체감상 길어서 20초로 — 물건이 어려우면 어차피 라운드 결과에서 쉬어간다
export const ROUND_DURATION_MS = 20_000;
// 라운드 시작 전 3·2·1 카운트다운 — 제시어를 전원이 읽고 동시에 출발하게 (공정성 + 긴장감)
export const COUNTDOWN_MS = 3_000;
const DEFAULT_TOTAL_ROUNDS = 3;

type FetchGameEvent =
  | {
      type: 'round-start';
      round: number;
      totalRounds: number;
      target: string;
      hostNickname: string;
      /** 방장 시계 기준 시작 시각(epoch ms) — 수신 지연으로 클라이언트마다 타이머가
       *  어긋나지 않게 전원이 이 값을 기준으로 계산한다. (기기 간 시계 오차는 남는 한계 —
       *  최종적으론 Spring이 라운드 시작/데드라인의 권위 시계가 되어야 함) */
      startedAt: number;
    }
  | { type: 'success'; nickname: string; elapsedMs: number }
  | { type: 'round-end' } // 방장이 라운드 마감(전원 성공 or 타임아웃)을 선언
  | { type: 'game-end' }
  | { type: 'exit' }; // 게임 종료 후 대기방 복귀

export interface SuccessEntry {
  nickname: string;
  elapsedMs: number;
}

export type FetchPhase = 'idle' | 'playing' | 'roundResult' | 'ended';

export interface FetchGameState {
  phase: FetchPhase;
  round: number;
  totalRounds: number;
  target: string | null;
  /** 게임을 시작한 방장 닉네임 — 라운드 진행(다음/종료) 권한 판별용 */
  hostNickname: string | null;
  /** 이번 라운드 시작 시각(내 로컬 수신 기준) — 타이머/기록 계산용 */
  startedAt: number;
  /** 이번 라운드 성공자 (도착 순서) */
  successes: SuccessEntry[];
  /** 코스 누적 점수 (닉네임 → 점수). 순위 점수는 백엔드 GameScoreService와 동일하게 5/4/3/2 */
  totals: Record<string, number>;
}

const RANK_SCORES = [5, 4, 3, 2];

const initialState: FetchGameState = {
  phase: 'idle',
  round: 0,
  totalRounds: DEFAULT_TOTAL_ROUNDS,
  target: null,
  hostNickname: null,
  startedAt: 0,
  successes: [],
  totals: {},
};

export function useFetchGame() {
  const { localParticipant } = useLocalParticipant();
  const myNickname = localParticipant.name || '나';
  const [state, setState] = useState<FetchGameState>(initialState);
  // 방장이 다음 라운드 제시어를 뽑을 때 쓸 풀 (최초 시작 시 1회 로딩)
  const labelPoolRef = useRef<string[]>([]);

  const apply = useCallback((event: FetchGameEvent) => {
    setState((prev) => {
      switch (event.type) {
        case 'round-start':
          return {
            ...prev,
            phase: 'playing',
            round: event.round,
            totalRounds: event.totalRounds,
            target: event.target,
            hostNickname: event.hostNickname,
            startedAt: event.startedAt,
            successes: [],
            // 1라운드 시작이면 누적도 초기화 (새 게임)
            totals: event.round === 1 ? {} : prev.totals,
          };
        case 'success': {
          if (prev.phase !== 'playing') return prev;
          if (prev.successes.some((s) => s.nickname === event.nickname)) return prev;
          const successes = [...prev.successes, { nickname: event.nickname, elapsedMs: event.elapsedMs }];
          const rank = successes.length - 1;
          const totals = {
            ...prev.totals,
            [event.nickname]: (prev.totals[event.nickname] ?? 0) + (RANK_SCORES[rank] ?? 1),
          };
          return { ...prev, successes, totals };
        }
        case 'round-end':
          // playing이 아니어도(이벤트 도착 순서 꼬임 등) 게임 중이면 결과 화면으로 —
          // 팝업이 일부 클라이언트에서 안 뜨는 간헐 현상 방지
          return prev.phase === 'playing' || prev.phase === 'roundResult'
            ? { ...prev, phase: 'roundResult' }
            : prev;
        case 'game-end':
          return { ...prev, phase: 'ended' };
        case 'exit':
          return initialState;
        default:
          return prev;
      }
    });
  }, []);

  const { send } = useDataChannel(FETCH_TOPIC, (msg) => {
    try {
      apply(JSON.parse(decoder.decode(msg.payload)) as FetchGameEvent);
    } catch {
      // fetch 게임 이벤트가 아닌 payload는 무시
    }
  });

  // 데이터 채널은 발신자에게 되돌아오지 않으므로 보낸 뒤 로컬에도 즉시 적용한다
  const broadcast = useCallback(
    (event: FetchGameEvent) => {
      void send(encoder.encode(JSON.stringify(event)), { reliable: true });
      apply(event);
    },
    [send, apply],
  );

  // 개발용 제시어 고정 — /dev/fetch?target=휴대폰 처럼 startGame에 넘기면 전 라운드 고정.
  // 기본(미지정)은 풀에서 랜덤 (실플레이 모드).
  const fixedTargetRef = useRef<string | null>(null);

  // 한 게임(코스) 안에서 같은 제시어가 반복되지 않게 이미 나온 것을 기억한다.
  // 풀(12종) > 최대 라운드(10)라 항상 남는 후보가 있다. 게임 시작 시 초기화.
  const usedTargetsRef = useRef<Set<string>>(new Set());

  const pickTarget = useCallback(async () => {
    if (fixedTargetRef.current) return fixedTargetRef.current;
    if (labelPoolRef.current.length === 0) {
      labelPoolRef.current = await aiApi.labels();
    }
    const remaining = labelPoolRef.current.filter((label) => !usedTargetsRef.current.has(label));
    // 풀이 바닥나면(라운드 > 풀 크기) 그때만 전체에서 다시 뽑는다
    const pool = remaining.length > 0 ? remaining : labelPoolRef.current;
    const target = pool[Math.floor(Math.random() * pool.length)];
    usedTargetsRef.current.add(target);
    return target;
  }, []);

  /** [방장 전용] 게임 시작 — 제시어를 뽑아 1라운드 개시. fixedTarget은 개발용 고정 제시어 */
  const startGame = useCallback(
    async (totalRounds: number = DEFAULT_TOTAL_ROUNDS, fixedTarget?: string) => {
      fixedTargetRef.current = fixedTarget ?? null;
      usedTargetsRef.current = new Set(); // 새 게임 — 제시어 중복 방지 기록 초기화
      const target = await pickTarget();
      broadcast({
        type: 'round-start',
        round: 1,
        totalRounds,
        target,
        hostNickname: myNickname,
        startedAt: Date.now(),
      });
    },
    [broadcast, pickTarget, myNickname],
  );

  /** [방장 전용] 다음 라운드 (라운드 결과 화면에서) */
  const nextRound = useCallback(async () => {
    const target = await pickTarget();
    broadcast({
      type: 'round-start',
      round: stateRef.current.round + 1,
      totalRounds: stateRef.current.totalRounds,
      target,
      hostNickname: stateRef.current.hostNickname ?? myNickname,
      startedAt: Date.now(),
    });
  }, [broadcast, pickTarget, myNickname]);

  /** 내 성공 보고 (인식 훅이 호출) */
  const reportSuccess = useCallback(
    (elapsedMs: number) => broadcast({ type: 'success', nickname: myNickname, elapsedMs }),
    [broadcast, myNickname],
  );

  /** [방장 전용] 라운드 마감 선언 (타임아웃 or 전원 성공 시) */
  const endRound = useCallback(() => {
    if (stateRef.current.round >= stateRef.current.totalRounds) {
      broadcast({ type: 'round-end' });
      broadcast({ type: 'game-end' });
    } else {
      broadcast({ type: 'round-end' });
    }
  }, [broadcast]);

  /** [방장 전용] 대기방 복귀 */
  const exitGame = useCallback(() => broadcast({ type: 'exit' }), [broadcast]);

  // 콜백들이 최신 state를 읽을 수 있게 ref 미러링 (폴링/타이머 콜백에서 stale closure 방지)
  const stateRef = useRef(state);
  stateRef.current = state;

  return { state, myNickname, startGame, nextRound, reportSuccess, endRound, exitGame };
}
