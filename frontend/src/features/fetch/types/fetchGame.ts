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

// 스킵 버튼 노출 지연 — 제시어를 보고 주변을 훑을 시간. 반사적 스킵 러시만 막으면 되므로
// 짧게 둔다 (가결이 전원 만장일치라 성급한 1표는 어차피 무해). 플레이테스트로 조정.
export const SKIP_VOTE_DELAY_MS = 5_000;

export interface FetchGameState {
  phase: FetchPhase;
  round: number;
  totalRounds: number;
  target: string | null;
  startedAt: number;
  deadlineAt?: number | null;
  successes: FetchSuccessEntry[];
  totals: Record<string, number>;
  /** 현재 라운드 스킵 투표자 participantId 목록 (서버 브로드캐스트/스냅샷 원본) */
  skipVotes: string[];
  /** 스킵 가결 분모 — 접속 참가자 수. 서버가 이벤트마다 실어 준다 */
  skipRequired: number;
  /** 직전 라운드가 스킵 가결로 끝났는지 — roundResult 화면의 배너 구분용 */
  roundSkipped: boolean;
}
