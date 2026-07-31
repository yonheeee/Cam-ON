import { handleExpiredSession, isSessionDead } from '../../session/lib/sessionExpiry';
// Spring 물건 가져오기 REST 클라이언트 (제출 하나뿐 — 라운드 진행은 전부 서버가 STOMP로 밀어준다).
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export class FetchGameApiError extends Error {
  code?: string;

  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

export interface FetchSubmissionResponse {
  round: number;
  participantId: string;
  /** 이번 라운드 도착 순번(1등 도착=1) — 확정 순위표는 round:end의 scores가 원본 */
  rank: number;
  score: number;
}

export interface FetchSuccessEntry {
  participantId: string;
  rank: number;
  score: number;
  submittedAt: number;
}

export interface FetchScoreEntry {
  participantId: string;
  score: number;
  rank: number;
}

export interface FetchObjectStateResponse {
  round: number;
  totalRounds: number;
  target: string;
  startedAt: number;
  deadlineAt: number;
  status: 'PLAYING' | 'ENDED';
  successes: FetchSuccessEntry[];
  totals: FetchScoreEntry[];
}

export const fetchGameApi = {
  /** STOMP 이벤트를 놓치거나 재연결한 경우 Redis의 현재 라운드 원본으로 화면을 복구한다. */
  async getState(gameId: number, accessToken: string): Promise<FetchObjectStateResponse> {
    const response = await fetch(`${BASE_URL}/api/games/${gameId}/fetch-object/state`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      if (isSessionDead(response.status)) handleExpiredSession();
      throw new FetchGameApiError(
        body?.message ?? `게임 상태 조회 실패 (HTTP ${response.status})`,
        body?.code,
      );
    }
    return body.data as FetchObjectStateResponse;
  },

  /** 인식 성공 보고. 서버가 도착 순서로 순번/점수를 원자적으로 확정한다.
   *  라운드가 이미 닫혔거나(타임아웃 직후 경합) 중복 제출이면 409 — 호출부에서 조용히 무시. */
  async submit(
    gameId: number,
    round: number,
    accessToken: string,
    confidence?: number,
    targetScore?: number,
  ): Promise<FetchSubmissionResponse> {
    const response = await fetch(`${BASE_URL}/api/games/${gameId}/fetch-object/submissions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({ round, confidence, targetScore }),
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      // 토큰이 죽었으면 이 화면에서 할 수 있는 게 없다 — 세션을 정리하고 첫 화면으로 되돌린다.
      if (isSessionDead(response.status)) handleExpiredSession();
      throw new FetchGameApiError(body?.message ?? `제출 실패 (HTTP ${response.status})`, body?.code);
    }
    return body.data as FetchSubmissionResponse;
  },
};
