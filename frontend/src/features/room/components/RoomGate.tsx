import { useState } from 'react';
import { useNavigate } from 'react-router';
import { createSession, SessionApiError } from '../../session/api/sessionApi';
import { saveSession } from '../../session/lib/sessionStorage';
import { roomApi, RoomApiError } from '../api/roomApi';
import { saveRoom } from '../lib/roomStorage';

// 백엔드 CreateSessionRequest 검증 규칙(@Size(max=8), @Pattern("^[\p{L}\p{N}]+$"))과 동일 —
// 서버 왕복 없이 바로 피드백 주려고 프론트에도 미러링. 최종 검증은 항상 백엔드가 한다.
const NICKNAME_PATTERN = /^[\p{L}\p{N}]{1,8}$/u;

type Mode = 'create' | 'join';

// 방 생성/입장 없이 닉네임만으로 세션(회원)이 먼저 만들어지는 걸 막기 위해, "방 생성"/"방 입장"을
// 먼저 고르게 하고 그 안에서 닉네임을 받아 세션 발급 + 방 생성(또는 입장)을 한 번에 처리한다.
export function RoomGate() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode | null>(null);
  const [nickname, setNickname] = useState('');
  const [maxPlayers, setMaxPlayers] = useState(4);
  const [roomCode, setRoomCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!NICKNAME_PATTERN.test(nickname)) {
      setError('닉네임은 공백/기호 없이 1~8자여야 합니다.');
      return;
    }
    if (mode === 'join' && !roomCode.trim()) {
      setError('방 코드를 입력하세요.');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const session = await createSession(nickname);
      saveSession(session);

      const result = mode === 'create'
        ? await roomApi.createRoom(maxPlayers, session.accessToken)
        : await roomApi.joinRoom(roomCode.trim(), session.accessToken);

      saveRoom({ roomId: result.room.roomId, livekitToken: result.livekitToken });
      navigate(`/rooms/${result.room.roomId}`);
    } catch (err) {
      if (err instanceof SessionApiError || err instanceof RoomApiError) {
        setError(err.message);
      } else {
        setError(mode === 'create' ? '방 생성 실패' : '방 입장 실패');
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (mode === null) {
    return (
      <div>
        <h1>시작하기</h1>
        <button type="button" onClick={() => setMode('create')}>방 생성</button>
        <button type="button" onClick={() => setMode('join')}>방 입장</button>
      </div>
    );
  }

  return (
    <div>
      <h1>{mode === 'create' ? '방 생성' : '방 입장'}</h1>
      <form onSubmit={handleSubmit}>
        <label>
          닉네임
          <input
            value={nickname}
            onChange={(event) => setNickname(event.target.value)}
            maxLength={8}
            disabled={submitting}
          />
        </label>
        {mode === 'create' && (
          <label>
            인원(2~4)
            <input
              type="number"
              min={2}
              max={4}
              value={maxPlayers}
              onChange={(event) => setMaxPlayers(Number(event.target.value))}
              disabled={submitting}
            />
          </label>
        )}
        {mode === 'join' && (
          <label>
            방 코드
            <input
              value={roomCode}
              onChange={(event) => setRoomCode(event.target.value)}
              disabled={submitting}
            />
          </label>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? '처리 중...' : mode === 'create' ? '방 생성' : '방 입장'}
        </button>
        <button type="button" onClick={() => setMode(null)} disabled={submitting}>
          뒤로
        </button>
      </form>
      {error && <p>{error}</p>}
    </div>
  );
}
