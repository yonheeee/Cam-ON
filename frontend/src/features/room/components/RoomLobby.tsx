import { useState } from 'react';
import { useRoomLobby } from '../hooks/useRoomLobby';
import './RoomLobby.css';

interface RoomLobbyProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  onStartGame: (participantTokens: string[]) => void;
  starting: boolean;
  startError: string | null;
}

// 화상통화 화면(VideoCallRoom) 안에 얹혀서 게임 시작 전까지 떠 있는 대기방 패널이다.
// 손 인식은 게임이 시작돼야 켜지므로 여기서는 화면(비디오)·닉네임·준비 상태·방장만 보여준다.
// 참가자는 항상 닉네임으로만 표시한다 — participantId는 내부 식별/API 호출용이고 화면에 안 띄운다.
export function RoomLobby({
  roomId,
  accessToken,
  participantId,
  onStartGame,
  starting,
  startError,
}: RoomLobbyProps) {
  const { room, error, toggleReady } = useRoomLobby(roomId, accessToken);
  const [copied, setCopied] = useState(false);

  if (error) return <div className="room-lobby">방 정보를 불러오지 못했습니다: {error}</div>;
  if (!room) return <div className="room-lobby">불러오는 중...</div>;

  const self = room.participants.find((p) => p.participantId === participantId);

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(room.roomCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard 접근이 막힌 환경(비 HTTPS 등)에서는 조용히 넘어간다 — 코드는 화면에 그대로 보인다.
    }
  };

  return (
    <div className="room-lobby">
      <p>
        방 코드: {room.roomCode}{' '}
        <button type="button" onClick={copyCode}>
          {copied ? '복사됨!' : '복사'}
        </button>
      </p>
      <ul>
        {room.participants.map((p) => (
          <li key={p.participantId}>
            {p.nickname}
            {p.participantId === room.hostParticipantId && ' (방장)'}
            {' - '}
            {p.ready ? '준비완료' : '대기중'}
          </li>
        ))}
      </ul>
      {self && (
        <button type="button" onClick={() => toggleReady(!self.ready)}>
          {self.ready ? '준비 취소' : '준비 완료'}
        </button>
      )}
      <button
        type="button"
        disabled={starting}
        onClick={() => onStartGame(room.participants.map((p) => p.participantId))}
      >
        {starting ? '시작 중...' : '게임 시작'}
      </button>
      {startError && <p className="room-lobby__error">{startError}</p>}
    </div>
  );
}
