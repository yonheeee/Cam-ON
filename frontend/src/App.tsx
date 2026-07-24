import { useState } from 'react';
import { VideoCallRoom } from './features/webrtc/components/VideoCallRoom';
import { NicknameGate } from './features/session/components/NicknameGate';
import { loadSession, type StoredSession } from './features/session/lib/sessionStorage';
import { RoomGate, type RoomReady } from './features/room/components/RoomGate';

function App() {
  const [session, setSession] = useState<StoredSession | null>(() => loadSession());
  const [room, setRoom] = useState<RoomReady | null>(null);

  if (!session) {
    return <NicknameGate onReady={setSession} />;
  }

  if (!room) {
    return <RoomGate accessToken={session.accessToken} onReady={setRoom} />;
  }

  return (
    <VideoCallRoom
      accessToken={session.accessToken}
      token={room.livekitToken}
      roomId={room.roomId}
    />
  );
}

export default App;
