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
  // 판(round) 안의 교환 번호 — (round, exchange)가 바뀌면 프론트가 이 스킬을 다시 조회한다.
  exchange: number;
  skillId: number;
  skillName: string;
  skillDesc: string | null;
  gestures: GestureStep[];
  effect: EffectDto;
  // 바로 다음 교환에 나올 스킬 예고 — 없으면 null.
  nextSkill: NextSkillPreview | null;
}

export interface AttackResponse {
  round: number;
  exchange: number;
  attackerToken: string;
  skillId: number;
}

export interface TargetResponse {
  round: number;
  exchange: number;
  attackerToken: string;
  targetToken: string;
  skillId: number;
  damage: number;
  targetHpAfter: number;
  targetEliminated: boolean;
  // 이 교환으로 판이 끝났는가(최후 1인).
  boutEnded: boolean;
  // 이 공격이 게임 전체를 끝냈는가(마지막 판 종료).
  gameEnded: boolean;
}

export interface RankingEntry {
  token: string;
  rank: number;
}

// 라운드 진행의 서버 기준 단계. 백엔드 NinjaPhase와 1:1.
export type NinjaPhase = 'ROUND' | 'INTERMISSION' | 'ENDED';

// INTERMISSION 구간에서 "방금 무슨 공격이 들어갔는지" 스냅샷. 타임아웃 인터미션엔 null.
export interface LastAttack {
  attackerToken: string;
  targetToken: string;
  skillId: number | null;
  damage: number;
  targetHpAfter: number;
  targetEliminated: boolean;
}

export interface NinjaStateResponse {
  // round=판(1..totalRounds), exchange=판 안의 교환. alivePlayers/hp는 "현재 판" 기준(판마다 리셋).
  round: number;
  exchange: number;
  totalRounds: number;
  alivePlayers: string[];
  hp: Record<string, number>;
  currentAttackerToken: string | null;
  // 게임이 끝나기 전엔 빈 배열. WS(ninja:game-ended) 없이도 폴링만으로 최종 순위를 알 수 있게
  // 백엔드가 GET .../state에 같이 실어준다(순위는 누적 점수순).
  ranking: RankingEntry[];
  // 서버 주도 인터미션: 진행/전환을 클라 로컬 타이머가 아니라 이 서버 기준 시각(ISO 문자열)으로만
  // 판단한다. ROUND일 땐 effectUntil/nextRoundAt/lastAttack은 null.
  phase: NinjaPhase | null;
  effectUntil: string | null;
  nextRoundAt: string | null;
  lastAttack: LastAttack | null;
  // 판을 가로질러 누적된 참가자별 점수(최종 발표 합산용).
  sessionTotals: Record<string, number>;
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

  // TEMP — 테스트 중 판을 통째로 리셋하는 버튼용. room/course/session 도메인이 완성되면 지운다.
  reset: (roomId: string, accessToken: string) =>
    request<void>(`/api/dev/ninja/reset`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ roomId }),
    }),
};
