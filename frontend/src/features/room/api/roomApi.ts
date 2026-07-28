// Spring 백엔드 domain/room REST 클라이언트. ninjaApi.ts와 동일한 base URL/에러 처리 패턴.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export interface ParticipantResponse {
  participantId: string;
  nickname: string;
  role: 'HOST' | 'MEMBER';
  ready: boolean;
  connectionStatus: string;
}

export interface RoomSnapshotResponse {
  roomId: string;
  roomCode: string;
  maxPlayers: number;
  status: string;
  hostParticipantId: string;
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
    throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
  }
  return body.data as T;
}

export const roomApi = {
  createRoom: (maxPlayers: number, accessToken: string) =>
    request<CreateRoomResult>('/api/rooms', accessToken, {
      method: 'POST',
      body: JSON.stringify({ maxPlayers }),
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
      const body = await response.json().catch(() => null);
      throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
    }
  },

  updateReady: (roomId: string, ready: boolean, accessToken: string) =>
    request<UpdateReadyResult>(`/api/rooms/${roomId}/members/me/ready`, accessToken, {
      method: 'PATCH',
      body: JSON.stringify({ ready }),
    }),

  // 방장이 대기방에서 게임을 시작한다. 참가자 토큰은 서버가 방의 실제 참가자 목록에서 만들므로
  // 클라이언트가 넘기지 않는다. 서버가 방장 여부·전원 준비를 검증하고 방을 PLAYING으로 전환한 뒤
  // 세션을 열며 game:started를 브로드캐스트한다.
  startGame: (roomId: string, gameId: number, totalRounds: number, accessToken: string) =>
    request<StartGameResult>(`/api/rooms/${roomId}/start`, accessToken, {
      method: 'POST',
      body: JSON.stringify({ gameId, totalRounds }),
    }),
};
