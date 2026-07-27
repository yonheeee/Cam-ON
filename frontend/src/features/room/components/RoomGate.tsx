import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { createSession, SessionApiError } from '../../session/api/sessionApi';
import { saveSession } from '../../session/lib/sessionStorage';
import { roomApi, RoomApiError } from '../api/roomApi';
import { saveRoom } from '../lib/roomStorage';

// 백엔드 CreateSessionRequest 검증 규칙(@Size(max=8), @Pattern("^[\p{L}\p{N}]+$"))과 동일 —
// 서버 왕복 없이 바로 피드백 주려고 프론트에도 미러링. 최종 검증은 항상 백엔드가 한다.
const NICKNAME_PATTERN = /^[\p{L}\p{N}]{1,8}$/u;

type Mode = 'create' | 'join';

// 방 생성/입장 없이 닉네임만으로 세션(회원)이 먼저 만들어지는 걸 막기 위해, 랜딩에서
// "방 만들기"/"코드로 참여"를 먼저 고르게 하고(?mode=create|join) 여기서 닉네임을 받아
// 세션 발급 + 방 생성(또는 입장)을 한 번에 처리한다.
export function RoomGate() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const modeParam = searchParams.get('mode');
  const mode: Mode | null =
    modeParam === 'create' || modeParam === 'join' ? modeParam : null;
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
        // 방 코드는 대문자(A-Z, 2-9)로만 생성되는데 백엔드 매칭이 대소문자를 구분한다 —
        // 소문자로 입력하면 "존재하지 않는 방"이 되므로 대문자로 정규화해서 보낸다.
        : await roomApi.joinRoom(roomCode.trim().toUpperCase(), session.accessToken);

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

  // 모드 선택 화면은 랜딩(/)이 담당 — 모드 없이 직접 들어오면 랜딩으로 돌려보낸다.
  if (mode === null) {
    return <Navigate to="/" replace />;
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
              onChange={(event) => setRoomCode(event.target.value.toUpperCase())}
              style={{ textTransform: 'uppercase' }}
              disabled={submitting}
            />
          </label>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? '처리 중...' : mode === 'create' ? '방 생성' : '방 입장'}
        </button>
        <button type="button" onClick={() => navigate('/')} disabled={submitting}>
          뒤로
        </button>
      </form>
      {error && <p>{error}</p>}
    </div>
  );
}
