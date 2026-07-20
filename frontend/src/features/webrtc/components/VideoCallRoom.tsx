import { useState } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import { VideoPresets, type RoomOptions } from 'livekit-client';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GestureBoard } from '../../gesture/components/GestureBoard';
import '@livekit/components-styles';
import './VideoCallRoom.css';

// adaptiveStream을 껐더니(항상 최고 레이어 강제) 참가자 4명을 한 기기에서 테스트할 때
// 재생 지연(estimatedPropagationDelayNs)이 거의 2분까지 쌓이는 걸 로그로 확인해서 다시 켰다.
// 실제 서비스에선 참가자마다 다른 기기라 이 정도로 몰리진 않겠지만, 저사양 기기/네트워크에서
// 부드럽게 낮춰주는 안전장치 없이 최고화질을 강제하는 리스크가 더 크다고 판단했다.
const roomOptions: RoomOptions = {
  adaptiveStream: true,
  dynacast: true,
  videoCaptureDefaults: {
    resolution: VideoPresets.h720.resolution,
  },
  publishDefaults: {
    simulcast: true,
    videoSimulcastLayers: [VideoPresets.h180, VideoPresets.h360, VideoPresets.h720],
    videoEncoding: {
      maxBitrate: 2_500_000,
      maxFramerate: 30,
    },
  },
};

// 토큰을 직접 입력받는 건 로컬 개발 전용이다.
// 실제 플로우에서는 방 입장 API 응답으로 Spring이 LiveKit 토큰을 내려준다 (TanStack Query로 교체 예정).
export function VideoCallRoom() {
  const [serverUrl, setServerUrl] = useState('ws://localhost:7880');
  const [token, setToken] = useState('');
  const [connected, setConnected] = useState(false);

  if (connected) {
    return (
      <LiveKitRoom
        serverUrl={serverUrl}
        token={token}
        connect
        video
        audio
        options={roomOptions}
        data-lk-theme="default"
        style={{ height: '100vh' }}
        onDisconnected={() => setConnected(false)}
      >
        <VideoConference />
        <GesturePanel />
        <GestureBoard />
      </LiveKitRoom>
    );
  }

  return (
    <div className="video-call-room video-call-room--join">
      <h1>LiveKit 연결 테스트</h1>
      <p>scripts/mint-dev-token.mjs로 발급한 토큰을 붙여넣고 입장한다.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (serverUrl.trim() && token.trim()) setConnected(true);
        }}
      >
        <label>
          Server URL
          <input value={serverUrl} onChange={(event) => setServerUrl(event.target.value)} />
        </label>
        <label>
          Access Token
          <textarea
            value={token}
            onChange={(event) => setToken(event.target.value)}
            rows={4}
            placeholder="node scripts/mint-dev-token.mjs <room> <name> 출력값을 붙여넣기"
          />
        </label>
        <button type="submit">입장</button>
      </form>
    </div>
  );
}
