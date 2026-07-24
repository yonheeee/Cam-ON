import { useState } from 'react';
import { VideoCallRoom } from './features/webrtc/components/VideoCallRoom';
import { NicknameGate } from './features/session/components/NicknameGate';
import { loadSession, type StoredSession } from './features/session/lib/sessionStorage';

function App() {
  const [session, setSession] = useState<StoredSession | null>(() => loadSession());

  if (!session) {
    return <NicknameGate onReady={setSession} />;
  }

  return <VideoCallRoom accessToken={session.accessToken} />;
}

export default App;
