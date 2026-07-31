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

/** 진행 상태 스냅샷. 닌자(GET .../ninja/state)·몸으로말해요(GET .../charades/state)와 같은 역할 —
 *  구독 직후/재접속 직후에 한 번 읽어 이벤트 공백을 메운다.
 *  [백엔드 미구현] 이 엔드포인트가 생기기 전까지 조회는 조용히 실패하고 이벤트만으로 진행한다. */
export interface FetchStateResponse {
  /** 아직 첫 라운드가 안 열렸으면 0 */
  round: number;
  totalRounds: number;
  /** 현재 라운드 제시어. 라운드가 없으면 null */
  target: string | null;
  /** epoch ms — 이 시각 기준 3초 카운트다운 + 20초 플레이 (round:start와 같은 값) */
  startedAt: number | null;
  status: 'READY' | 'PLAYING' | 'ROUND_ENDED' | 'FINISHED';
  /** 이번 라운드에 이미 성공한 사람들(도착 순) */
  successes: { participantId: string; rank: number }[];
  /** 세션 누적 점수 */
  totals: { participantId: string; score: number }[];
}

export const fetchGameApi = {
  /** 현재 라운드/제시어 스냅샷. 마운트 직후와 STOMP (재)연결 직후에 부른다. */
  async getState(gameId: number, accessToken: string): Promise<FetchStateResponse> {
    const response = await fetch(`${BASE_URL}/api/games/${gameId}/fetch-object/state`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      if (isSessionDead(response.status)) handleExpiredSession();
      throw new FetchGameApiError(body?.message ?? `조회 실패 (HTTP ${response.status})`, body?.code);
    }
    return body.data as FetchStateResponse;
  },

  /** 인식 성공 보고. 서버가 도착 순서로 순번/점수를 원자적으로 확정한다.
   *  라운드가 이미 닫혔거나(타임아웃 직후 경합) 중복 제출이면 409 — 호출부에서 조용히 무시. */
  async submit(gameId: number, round: number, accessToken: string): Promise<FetchSubmissionResponse> {
    const response = await fetch(`${BASE_URL}/api/games/${gameId}/fetch-object/submissions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({ round }),
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
