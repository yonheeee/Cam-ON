import { useState } from 'react';
import { VideoCallRoom } from './features/webrtc/components/VideoCallRoom';
import { RoomGate, type RoomReady } from './features/room/components/RoomGate';
import { RoomLobby } from './features/room/components/RoomLobby';

function App() {
  const [ready, setReady] = useState<RoomReady | null>(null);
  const [entered, setEntered] = useState(false);

  if (!ready) {
    return <RoomGate onReady={setReady} />;
  }

  if (!entered) {
    return (
      <RoomLobby
        roomId={ready.roomId}
        accessToken={ready.session.accessToken}
        participantId={ready.session.participantId}
        onEnter={() => setEntered(true)}
      />
    );
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
