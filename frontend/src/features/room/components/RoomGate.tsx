import { useState } from 'react';
import { roomApi, RoomApiError } from '../api/roomApi';

export interface RoomReady {
  roomId: string;
  livekitToken: string;
}

interface RoomGateProps {
  accessToken: string;
  onReady: (result: RoomReady) => void;
}

export function RoomGate({ accessToken, onReady }: RoomGateProps) {
  const [maxPlayers, setMaxPlayers] = useState(4);
  const [roomCode, setRoomCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await roomApi.createRoom(maxPlayers, accessToken);
      onReady({ roomId: result.room.roomId, livekitToken: result.livekitToken });
    } catch (err) {
      setError(err instanceof RoomApiError ? err.message : '방 생성 실패');
    } finally {
      setSubmitting(false);
    }
  };

  const handleJoin = async () => {
    if (!roomCode.trim()) {
      setError('방 코드를 입력하세요.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await roomApi.joinRoom(roomCode.trim(), accessToken);
      onReady({ roomId: result.room.roomId, livekitToken: result.livekitToken });
    } catch (err) {
      setError(err instanceof RoomApiError ? err.message : '방 입장 실패');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <h1>방 생성 / 입장</h1>

      <section>
        <h2>방 생성</h2>
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
        <button type="button" onClick={handleCreate} disabled={submitting}>
          방 생성
        </button>
      </section>

      <section>
        <h2>방 입장</h2>
        <label>
          방 코드
          <input
            value={roomCode}
            onChange={(event) => setRoomCode(event.target.value)}
            disabled={submitting}
          />
        </label>
        <button type="button" onClick={handleJoin} disabled={submitting}>
          방 입장
        </button>
      </section>

      {error && <p>{error}</p>}
    </div>
  );
}
