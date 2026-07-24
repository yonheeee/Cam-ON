import { Navigate, useParams } from 'react-router';
import { VideoCallRoom } from '../../webrtc/components/VideoCallRoom';
import { loadSession } from '../../session/lib/sessionStorage';
import { loadRoom } from '../lib/roomStorage';

// URL(/rooms/:roomId)로 직접 들어오거나 새로고침한 경우를 대비해, 세션/방 정보를
// sessionStorage에서 다시 읽는다. 없으면(다른 탭에서 연 새 세션 등) 처음 화면으로 돌려보낸다.
export function RoomPage() {
  const { roomId } = useParams();
  const session = loadSession();
  const room = loadRoom();

  if (!session || !room || !roomId || room.roomId !== roomId) {
    return <Navigate to="/" replace />;
  }

  return (
    <VideoCallRoom
      accessToken={session.accessToken}
      token={room.livekitToken}
      roomId={room.roomId}
      participantId={session.participantId}
    />
  );
}
