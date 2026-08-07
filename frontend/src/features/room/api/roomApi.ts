import { handleExpiredSession, isSessionDead } from '../../session/lib/sessionExpiry';

// Spring 백엔드 domain/room REST 클라이언트. ninjaApi.ts와 동일한 base URL/에러 처리 패턴.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export interface ParticipantResponse {
  participantId: string;
  nickname: string;
  role: 'HOST' | 'MEMBER';
  ready: boolean;
  connectionStatus: string;
  /**
   * 대기방 화면에 있는가. false면 코스 종합 결과에 아직 남아 있는 사람이다 — 복귀는 각자
   * 누르는 개별 행동이라 방을 떠난 게 아니므로, 대기방 타일에 자리를 지킨 채 "게임 중"으로
   * 표시한다.
   */
  inLobby: boolean;
}

export interface RoomSnapshotResponse {
  roomId: string;
  roomCode: string;
  maxPlayers: number;
  status: string;
  hostParticipantId: string;
  /**
   * 발표 시연용 방인가. 방 생성 시 정해지고 바뀌지 않는다 — 켜져 있으면 서버가 게임 콘텐츠를
   * 고정 시나리오(닌자 술법/데미지, 물건 제시어, 몸으로말해요 제시어)로 낸다.
   * 구버전 백엔드는 이 필드를 안 내려주므로 optional.
   */
  demoMode?: boolean;
  participants: ParticipantResponse[];
}

export interface CreateRoomResult {
  room: RoomSnapshotResponse;
  inviteUrl: string;
  livekitToken: string;
}

export interface JoinRoomResult {
  room: RoomSnapshotResponse;
  livekitToken: string;
}

export interface UpdateReadyResult {
  participantId: string;
  ready: boolean;
  allReady: boolean;
}

export interface StartGameResult {
  gameId: number;
  sessionSeq: number;
  totalRounds: number;
}

export class RoomApiError extends Error {
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
    // 토큰이 죽었으면 이 화면에서 할 수 있는 게 없다 — 세션을 정리하고 첫 화면으로 되돌린다.
    if (isSessionDead(response.status)) handleExpiredSession();
    throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as T;
}

export const roomApi = {
  // demoMode는 발표 시연용 옵션(랜딩 페이지의 시연 모드 토글). 무엇이 어떻게 바뀌는지는 전부
  // 서버가 정하고, 프론트는 "이 방을 시연 모드로 열어 달라"만 보낸다.
  createRoom: (maxPlayers: number, accessToken: string, demoMode = false) =>
    request<CreateRoomResult>('/api/rooms', accessToken, {
      method: 'POST',
      body: JSON.stringify({ maxPlayers, demoMode }),
    }),

  joinRoom: (roomCode: string, accessToken: string) =>
    request<JoinRoomResult>('/api/rooms/join', accessToken, {
      method: 'POST',
      body: JSON.stringify({ roomCode }),
    }),

  getRoom: (roomId: string, accessToken: string) =>
    request<RoomSnapshotResponse>(`/api/rooms/${roomId}`, accessToken),

  // 자발적 퇴장. 이 호출이 없으면 백엔드는 하트비트가 만료될 때까지(15초) 퇴장을 알 수 없고,
  // 그 사이 방장이 나간 방은 "방장 없이 참가자만" 있는 상태로 멈춰 있게 된다.
  // 204 No Content라 request()(body.data를 읽는다)를 쓰지 못하고 직접 처리한다.
  leaveRoom: async (roomId: string, accessToken: string): Promise<void> => {
    const response = await fetch(`${BASE_URL}/api/rooms/${roomId}/members/me`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) {
      if (isSessionDead(response.status)) handleExpiredSession();
      const body = await response.json().catch(() => null);
      throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
    }
  },

  // 방장의 참가자 강퇴. 서버가 방장 여부/대기방 상태를 검증하고, 성공 시
  // member:left(reason=KICKED)를 브로드캐스트한다(강퇴된 참가자는 재입장 불가).
  // leaveRoom과 같은 이유(204 No Content)로 request()를 쓰지 않는다.
  kickMember: async (roomId: string, participantId: string, accessToken: string): Promise<void> => {
    const response = await fetch(`${BASE_URL}/api/rooms/${roomId}/members/${participantId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) {
      if (isSessionDead(response.status)) handleExpiredSession();
      const body = await response.json().catch(() => null);
      throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
    }
  },

  updateReady: (roomId: string, ready: boolean, accessToken: string) =>
    request<UpdateReadyResult>(`/api/rooms/${roomId}/members/me/ready`, accessToken, {
      method: 'PATCH',
      body: JSON.stringify({ ready }),
    }),

  // 방장이 대기방에서 확정한 코스대로 진행을 시작한다. 무엇을 몇 라운드 할지는 코스에 이미
  // 들어 있고 참가자 목록도 서버가 직접 읽으므로 보낼 바디가 없다. 서버가 방장 여부·전원 준비·
  // 코스 유효성을 검증하고 방을 PLAYING으로 전환한 뒤 첫 세션을 열며 game:started를 쏜다.
  startGame: (roomId: string, accessToken: string) =>
    request<StartGameResult>(`/api/rooms/${roomId}/start`, accessToken, {
      method: 'POST',
    }),

  // 코스 종합 결과에서 대기방으로 복귀. 방장 전용이 아니라 참가자 각자가 부르며, 부른 사람만
  // 돌아간다(한 번에 전원이 들어오지 않는다). 가장 먼저 부른 요청이 점수 기록을 초기화하고 방을
  // WAITING으로 되돌린다. 서버는 course:member-returned를 브로드캐스트하고 화면 전환은 그
  // 이벤트가 담당한다 — 당사자는 결과 화면을 접고, 나머지는 그 사람 타일의 "게임 중"만 뗀다.
  // leaveRoom과 같은 이유(204 No Content)로 request()를 쓰지 않는다.
  returnToLobby: async (roomId: string, accessToken: string): Promise<void> => {
    const response = await fetch(`${BASE_URL}/api/rooms/${roomId}/return`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) {
      if (isSessionDead(response.status)) handleExpiredSession();
      const body = await response.json().catch(() => null);
      throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
    }
  },
};
