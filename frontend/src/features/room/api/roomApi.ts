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
};
