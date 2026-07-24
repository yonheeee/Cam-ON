import { useRoomLobby } from '../hooks/useRoomLobby';

interface RoomLobbyProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  onEnter: () => void;
}

export function RoomLobby({ roomId, accessToken, participantId, onEnter }: RoomLobbyProps) {
  const { room, error, toggleReady } = useRoomLobby(roomId, accessToken);

  if (error) return <p>방 정보를 불러오지 못했습니다: {error}</p>;
  if (!room) return <p>불러오는 중...</p>;

  const self = room.participants.find((p) => p.participantId === participantId);

  return (
    <div>
      <h1>대기방</h1>
      <p>방 코드: {room.roomCode}</p>
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
      <button type="button" onClick={onEnter}>
        입장하기
      </button>
    </div>
  );
}
