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

  // 창/탭이 닫힐 때 쓰는 퇴장 통보. 일반 fetch는 문서가 언로드되면 취소되므로 keepalive를 켜서
  // 요청이 살아남게 한다(sendBeacon과 달리 Authorization 헤더와 DELETE를 쓸 수 있어 위
  // leaveRoom과 같은 엔드포인트를 그대로 재사용한다). 언로드 중엔 응답을 읽을 수도, 사용자에게
  // 알릴 수도 없으니 결과를 보지 않고 실패는 삼킨다 — 못 닿아도 하트비트 스윕이 15초 뒤 정리한다.
  leaveRoomOnUnload: (roomId: string, accessToken: string): void => {
    try {
      void fetch(`${BASE_URL}/api/rooms/${roomId}/members/me`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${accessToken}` },
        keepalive: true,
      }).catch(() => {});
    } catch {
      // 언로드 시점엔 브라우저가 새 요청을 거절할 수 있다. 여기서 막혀도 페이지 종료를 방해하지 않는다.
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

  // 방장이 대기방에서 확정한 코스대로 진행을 시작한다. 무엇을 몇 라운드 할지는 코스에 이미
  // 들어 있고 참가자 목록도 서버가 직접 읽으므로 보낼 바디가 없다. 서버가 방장 여부·전원 준비·
  // 코스 유효성을 검증하고 방을 PLAYING으로 전환한 뒤 첫 세션을 열며 game:started를 쏜다.
  startGame: (roomId: string, accessToken: string) =>
    request<StartGameResult>(`/api/rooms/${roomId}/start`, accessToken, {
      method: 'POST',
    }),

  // [방장 전용] 코스 종합 결과에서 대기방으로 복귀. 서버가 점수 기록을 초기화하고 방을
  // WAITING으로 되돌린 뒤 course:reset을 브로드캐스트한다 — 화면 전환은 그 이벤트가 담당.
  // leaveRoom과 같은 이유(204 No Content)로 request()를 쓰지 않는다.
  returnToLobby: async (roomId: string, accessToken: string): Promise<void> => {
    const response = await fetch(`${BASE_URL}/api/rooms/${roomId}/return`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new RoomApiError(body?.message ?? `요청 실패 (HTTP ${response.status})`, body?.code);
    }
  },
};
