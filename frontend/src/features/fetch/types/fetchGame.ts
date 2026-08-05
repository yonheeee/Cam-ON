export const COUNTDOWN_MS = 3_000;
// 서버 PLAY_DURATION(FetchObjectGameService)과 같은 값이어야 한다 — deadlineAt이 없는
// 스냅샷 복구 경로에서 타이머 폴백 계산에 쓰인다. 20초 → 40초 (2026-08-04, 서버와 동시 변경).
export const ROUND_DURATION_MS = 40_000;

export interface FetchSuccessEntry {
  participantId: string;
  nickname: string;
  rank: number;
  score: number;
  elapsedMs: number;
}

export type FetchPhase = 'idle' | 'playing' | 'roundResult' | 'ended';

export interface FetchGameState {
  phase: FetchPhase;
  round: number;
  totalRounds: number;
  target: string | null;
  startedAt: number;
  deadlineAt?: number | null;
  successes: FetchSuccessEntry[];
  totals: Record<string, number>;
}
