// Spring 백엔드 domain/session REST 클라이언트. ninjaApi.ts와 동일한 base URL 계산 방식
// (window.location.hostname 기반) — 다른 기기에서 프론트를 열어도 항상 같은 머신의 백엔드를 찾는다.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export interface CreateSessionResult {
  participantId: string;
  nickname: string;
  accessToken: string;
}

export class SessionApiError extends Error {
  code?: string;

  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

export async function createSession(nickname: string): Promise<CreateSessionResult> {
  const response = await fetch(`${BASE_URL}/api/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nickname }),
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new SessionApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as CreateSessionResult;
}
