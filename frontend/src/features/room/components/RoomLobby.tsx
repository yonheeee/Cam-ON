import { useRoomLobby } from '../hooks/useRoomLobby';

interface RoomLobbyProps {
  roomId: string;
  accessToken: string;
  participantId: string;
}

// 화상통화 화면(VideoCallRoom) 안에 얹혀서 게임 시작 전까지 떠 있는 패널이다 —
// 별도 화면으로 분리하지 않는 이유는 대기방 단계에서부터 카메라/마이크가 이미 연결돼
// 있어야 하기 때문(AGENTS.md 준비 상태 = 카메라 권한 완료 AND 인식 테스트 통과).
export function RoomLobby({ roomId, accessToken, participantId }: RoomLobbyProps) {
  const { room, error, toggleReady } = useRoomLobby(roomId, accessToken);

  if (error) return <div>방 정보를 불러오지 못했습니다: {error}</div>;
  if (!room) return <div>불러오는 중...</div>;

  const self = room.participants.find((p) => p.participantId === participantId);

  return (
    <div>
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
    </div>
  );
}
