export const COUNTDOWN_MS = 3_000;
export const ROUND_DURATION_MS = 20_000;

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
