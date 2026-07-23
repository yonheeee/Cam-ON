// Spring 백엔드 domain/game/ninja REST 클라이언트.
// 대기방/방장 도메인이 아직 없어서 roomId(=gameId)는 backend의 DevRoomRepository가
// 들고 있는 고정 테스트 방 id를 그대로 하드코딩한다 — 방 도메인이 완성되면
// 이 상수를 실제 방 입장 흐름에서 받아온 roomId로 교체하면 된다(나머지 함수 시그니처는 안 바뀜).
// localhost로 하드코딩하면 같은 와이파이의 다른 기기에서 프론트를 열었을 때 "자기 자신의
// localhost:8080"을 찾아버린다 — 프론트를 접속한 호스트명을 그대로 재사용해서, 백엔드가
// 프론트와 같은 머신에 떠 있다는 전제 하에 어느 기기에서 열든 항상 맞는 주소를 가리키게 한다.
// 백엔드가 다른 머신에 있으면 VITE_API_BASE_URL을 명시적으로 지정해서 덮어쓰면 된다.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export interface GestureStep {
  seq: number;
  gestureName: string;
  gestureLabelKr: string;
}

export interface EffectDto {
  id: number;
  name: string;
  color: string;
  particleCount: number;
  life: number;
  radiusMin: number;
  radiusMax: number;
  speedMin: number;
  speedMax: number;
}

export interface NextSkillPreview {
  skillId: number;
  skillName: string;
  gestures: GestureStep[];
}

export interface RoundSkillResponse {
  round: number;
  skillId: number;
  skillName: string;
  skillDesc: string | null;
  gestures: GestureStep[];
  effect: EffectDto;
  // 마지막 라운드면 null.
  nextSkill: NextSkillPreview | null;
}

export interface AttackResponse {
  round: number;
  attackerToken: string;
  skillId: number;
}

export interface TargetResponse {
  round: number;
  attackerToken: string;
  targetToken: string;
  skillId: number;
  damage: number;
  targetHpAfter: number;
  targetEliminated: boolean;
  gameEnded: boolean;
}

export interface RankingEntry {
  token: string;
  rank: number;
}

export interface NinjaStateResponse {
  round: number;
  totalRounds: number;
  alivePlayers: string[];
  hp: Record<string, number>;
  currentAttackerToken: string | null;
  // 게임이 끝나기 전엔 빈 배열. WS(ninja:game-ended) 없이도 폴링만으로 최종 순위를 알 수 있게
  // 백엔드가 GET .../state에 같이 실어준다.
  ranking: RankingEntry[];
}

export class NinjaApiError extends Error {
  code?: string;

  constructor(message: string, code?: string) {
    super(message);
    this.code = code;
  }
}

async function request<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  headers.Authorization = `Bearer ${accessToken}`;

  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...headers, ...(init?.headers as Record<string, string> | undefined) },
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new NinjaApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as T;
}

export const ninjaApi = {
  getRoundSkill: (gameId: number, round: number, accessToken: string) =>
    request<RoundSkillResponse>(`/api/games/${gameId}/ninja/rounds/${round}/skill`, accessToken),

  getState: (gameId: number, accessToken: string) =>
    request<NinjaStateResponse>(`/api/games/${gameId}/ninja/state`, accessToken),

  submitAttack: (gameId: number, round: number, skillId: number, accessToken: string) =>
    request<AttackResponse>(`/api/games/${gameId}/ninja/rounds/${round}/attack`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ skillId }),
    }),

  submitTarget: (gameId: number, round: number, targetToken: string, accessToken: string) =>
    request<TargetResponse>(`/api/games/${gameId}/ninja/rounds/${round}/target`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ targetToken }),
    }),

  // TEMP — 대기방에서 방장이 "게임 시작"을 누르면 자동으로 열려야 할 세션을 수동으로 튼다.
  // backend의 DevNinjaSeedController(/api/dev/ninja/seed)가 없어지면 이 함수도 같이 지우면 된다.
  seed: (
    roomId: string,
    gameId: number,
    participantTokens: string[],
    totalRounds: number,
    accessToken: string,
  ) =>
    request<void>(`/api/dev/ninja/seed`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ roomId, gameId, participantTokens, totalRounds }),
    }),

  // TEMP — 테스트 중 판을 통째로 리셋하는 버튼용. seed와 마찬가지로 room/course/session
  // 도메인이 생기면 지운다.
  reset: (roomId: string, accessToken: string) =>
    request<void>(`/api/dev/ninja/reset`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ roomId }),
    }),
};
