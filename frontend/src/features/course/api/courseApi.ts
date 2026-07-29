// Spring 백엔드 domain/course + 게임 카탈로그 REST 클라이언트.
// roomApi.ts와 동일한 base URL/에러 처리 패턴.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

// games.name — 백엔드가 세션 시작 전략을 고르는 키이자 프론트가 아이콘/한글명을 매핑하는 키.
// gameId는 시드 순서에 따라 환경마다 달라질 수 있어 절대 하드코딩하지 않는다.
export type GameName = 'NINJA' | 'FETCH_OBJECT' | 'CHARADES';

export interface CatalogGame {
  gameId: number;
  name: GameName;
  description: string | null;
  minPlayers: number;
  maxPlayers: number;
  /** null이면 최소 라운드가 "참여자 수" — 방 인원에 따라 달라져 서버가 고정값으로 줄 수 없다 */
  minRounds: number | null;
  maxRounds: number | null;
  /** true면 코스에 담을 때 topicId가 필수 (몸으로 말해요) */
  requiresTopic: boolean;
  /** false면 서버가 아직 이 게임의 세션을 열 수 없다 — 목록에는 보이지만 담을 수 없다 */
  supported: boolean;
}

export interface CatalogTopic {
  topicId: number;
  name: string;
  /** 이 주제에 등록된 제시어 수. (인원 x 라운드)가 이 값을 넘으면 게임이 중간에 끊긴다 */
  missionCount: number;
}

export interface CourseItem {
  idx: number;
  gameId: number;
  gameName: GameName;
  roundCount: number;
  topicId: number | null;
  topicName: string | null;
}

export interface Course {
  items: CourseItem[];
  currentSessionSeq: number;
}

/** 저장 요청용 — 서버는 리스트 순서를 그대로 진행 순서로 쓴다 */
export interface CourseItemInput {
  gameId: number;
  roundCount: number;
  topicId: number | null;
}

export class CourseApiError extends Error {
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
    throw new CourseApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as T;
}

export const courseApi = {
  getGames: (accessToken: string) => request<CatalogGame[]>('/api/games', accessToken),

  getTopics: (gameId: number, accessToken: string) =>
    request<CatalogTopic[]>(`/api/games/${gameId}/topics`, accessToken),

  getCourse: (roomId: string, accessToken: string) =>
    request<Course>(`/api/rooms/${roomId}/course`, accessToken),

  // 전체 교체(PUT). 성공하면 서버가 member:game-updated를 브로드캐스트해 전원 화면이 맞춰진다.
  updateCourse: (roomId: string, items: CourseItemInput[], accessToken: string) =>
    request<Course>(`/api/rooms/${roomId}/course`, accessToken, {
      method: 'PUT',
      body: JSON.stringify({ items }),
    }),
};

// 게임 이름 → 화면 표시용 한글명. 서버의 description과 별개로 UI에서 쓰는 정식 게임 타이틀이다.
export const GAME_LABELS: Record<GameName, string> = {
  NINJA: '손은 눈보다 빠르다',
  FETCH_OBJECT: '엄마! 내 물건 어딨어?',
  CHARADES: '말하지 않아도 알아요',
};

// 라운드 하나가 무슨 단위인지 — 게임마다 뜻이 달라서 코스 편집 화면에 같이 보여준다.
export const ROUND_UNIT_HINTS: Record<GameName, string> = {
  NINJA: '1라운드 = 최후의 1인이 남을 때까지',
  FETCH_OBJECT: '1라운드 = 물건 하나',
  CHARADES: '1라운드 = 전원이 한 번씩 출제',
};

// 이 게임을 현재 인원으로 진행할 때의 최소 라운드 수.
// minRounds가 null인 게임(물건 가져오기)은 "참여자 수"가 하한이다.
export function minRoundsFor(game: CatalogGame, playerCount: number): number {
  return game.minRounds ?? Math.max(1, playerCount);
}

export function maxRoundsFor(game: CatalogGame): number {
  return game.maxRounds ?? 10;
}
