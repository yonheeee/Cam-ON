// 방 생성/입장 응답으로 받은 값 중 새로고침/페이지 이동 후에도 필요한 것(roomId, LiveKit 토큰)을
// sessionStorage에 보관한다. 세션 토큰과 마찬가지로 임시 세션이라 탭 닫으면 사라진다.
export interface StoredRoom {
  roomId: string;
  livekitToken: string;
}

const STORAGE_KEY = 'camon.room';

export function saveRoom(room: StoredRoom): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(room));
}

export function loadRoom(): StoredRoom | null {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StoredRoom;
  } catch {
    return null;
  }
}

export function clearRoom(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}
