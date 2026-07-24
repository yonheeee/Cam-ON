import { useState } from 'react';
import { VideoCallRoom } from './features/webrtc/components/VideoCallRoom';
import { RoomGate, type RoomReady } from './features/room/components/RoomGate';

function App() {
  const [ready, setReady] = useState<RoomReady | null>(null);

  if (!ready) {
    return <RoomGate onReady={setReady} />;
  }

  return (
    <VideoCallRoom
      accessToken={ready.session.accessToken}
      token={ready.livekitToken}
      roomId={ready.roomId}
    />
  );
}

export default App;
