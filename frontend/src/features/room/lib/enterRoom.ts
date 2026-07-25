import { createSession } from '../../session/api/sessionApi';
import { saveSession } from '../../session/lib/sessionStorage';
import { roomApi } from '../api/roomApi';
import { saveRoom } from '../lib/roomStorage';

// 백엔드 CreateSessionRequest 검증 규칙(@Size(max=8), @Pattern("^[\p{L}\p{N}]+$"))과 동일 —
// 서버 왕복 없이 바로 피드백 주려고 프론트에도 미러링. 최종 검증은 항상 백엔드가 한다.
export const NICKNAME_PATTERN = /^[\p{L}\p{N}]{1,8}$/u;

// 방 코드는 6자(혼동 문자를 뺀 대문자/숫자). 형식만 프론트에서 거르고 실존 여부는 join 시 백엔드가 판정.
export const ROOM_CODE_PATTERN = /^[A-Z0-9]{6}$/;

export type EnterMode = 'create' | 'join';

interface EnterRoomParams {
  mode: EnterMode;
  nickname: string;
  /** mode === 'join'일 때 필수 */
  roomCode?: string;
  /** mode === 'create'일 때 사용 (기본 4) */
  maxPlayers?: number;
}

// 닉네임만으로 세션(회원)이 먼저 만들어지는 걸 막기 위해, 세션 발급 + 방 생성(또는 입장)을
// 한 번에 처리한다. 성공 시 세션/방 정보를 sessionStorage에 저장하고 roomId를 반환한다.
export async function enterRoom({ mode, nickname, roomCode, maxPlayers = 4 }: EnterRoomParams): Promise<string> {
  const session = await createSession(nickname);
  saveSession(session);

  const result = mode === 'create'
    ? await roomApi.createRoom(maxPlayers, session.accessToken)
    // 방 코드는 대문자로만 생성되는데 백엔드 매칭이 대소문자를 구분한다 — 대문자로 정규화해서 보낸다.
    : await roomApi.joinRoom((roomCode ?? '').trim().toUpperCase(), session.accessToken);

  saveRoom({ roomId: result.room.roomId, livekitToken: result.livekitToken });
  return result.room.roomId;
}
