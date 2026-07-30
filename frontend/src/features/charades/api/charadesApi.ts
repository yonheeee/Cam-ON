// Spring 백엔드 domain/game/charades REST 클라이언트.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export interface CharadesWordResponse {
  round: number;
  turn: number;
  word: string;
  expiresAt: string;
}

export interface CharadesGuessResponse {
  round: number;
  turn: number;
  correct: boolean;
}

export type CharadesStatus =
  | 'READY'
  | 'PLAYING'
  | 'CORRECT'
  | 'TIMEOUT'
  | 'INVALIDATED'
  | 'FINISHED';

export interface CharadesStateResponse {
  round: number;
  totalRounds: number;
  turn: number;
  totalTurnsInRound: number;
  presenterId: string | null;
  expiresAt: string | null;
  status: CharadesStatus;
}

export class CharadesApiError extends Error {
  code?: string;

  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

async function request<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
      ...(init?.headers as Record<string, string> | undefined),
    },
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new CharadesApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as T;
}

export const charadesApi = {
  getState: (gameId: number, accessToken: string) =>
    request<CharadesStateResponse>(`/api/games/${gameId}/charades/state`, accessToken),

  // 표현자만 호출 가능(백엔드가 participantId로 검증) — 관전자가 호출하면 403.
  getCurrentWord: (gameId: number, accessToken: string) =>
    request<CharadesWordResponse>(`/api/games/${gameId}/charades/word`, accessToken),

  submitGuess: (gameId: number, text: string, accessToken: string) =>
    request<CharadesGuessResponse>(`/api/games/${gameId}/charades/guesses`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),
};
