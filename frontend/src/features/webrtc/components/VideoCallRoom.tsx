import { useCallback, useState } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import { VideoPresets, type RoomOptions } from 'livekit-client';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GestureBoard } from '../../gesture/components/GestureBoard';
import { NinjaGamePanel } from '../../ninja/components/NinjaGamePanel';
import {
  type GameStartedPayload,
  useRoomGameStarted,
} from '../../ninja/hooks/useRoomGameStarted';
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

// LiveKit Cloud 프로젝트 서버 URL — 고정값이라 매번 입력받을 필요 없음.
const LIVEKIT_SERVER_URL = 'wss://plaiground-gkmfgv1j.livekit.cloud';

interface VideoCallRoomProps {
  // 닉네임 세션(NicknameGate)에서 이미 발급받은 백엔드 accessToken.
  accessToken: string;
  // 방 생성/입장(RoomGate) 응답에서 이미 받은 LiveKit 토큰과 roomId — 더는 직접 입력받지 않는다.
  token: string;
  roomId: string;
}

export function VideoCallRoom({ accessToken, token, roomId }: VideoCallRoomProps) {
  const [gameId, setGameId] = useState('');
  const [activeGameId, setActiveGameId] = useState<number | null>(null);
  const [activeSessionSeq, setActiveSessionSeq] = useState<number | null>(null);
  const [connected, setConnected] = useState(false);
  const [connectionError, setConnectionError] = useState<string | null>(null);

  const handleGameStarted = useCallback((payload: GameStartedPayload) => {
    setActiveGameId(payload.gameId);
    setActiveSessionSeq(payload.sessionSeq);
  }, []);

  useRoomGameStarted(
    connected ? roomId : null,
    connected ? accessToken : null,
    handleGameStarted,
  );

  if (connected) {
    return (
      <>
        {connectionError && (
          <div className="video-call-room__connection-error">LiveKit 연결 실패: {connectionError}</div>
        )}
        <LiveKitRoom
          serverUrl={LIVEKIT_SERVER_URL}
          token={token}
          connect
          video
          audio
          options={roomOptions}
          data-lk-theme="default"
          style={{ height: '100vh' }}
          onConnected={() => setConnectionError(null)}
          onDisconnected={() => setConnected(false)}
          onError={(err) => setConnectionError(err.message)}
        >
          <VideoConference />
          <GesturePanel />
          <GestureBoard />
          {activeSessionSeq !== null && (
            <div className="video-call-room__session">
              진행 세션 {activeSessionSeq}
            </div>
          )}
          {activeGameId !== null && (
            <NinjaGamePanel
              roomId={roomId}
              gameId={activeGameId}
              accessToken={accessToken}
            />
          )}
        </LiveKitRoom>
      </>
    );
  }

  return (
    <div className="video-call-room video-call-room--join">
      <h1>게임 선택</h1>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (Number(gameId) > 0) {
            setActiveGameId(Number(gameId));
            setConnected(true);
          }
        }}
      >
        <label>
          Game ID
          <input
            type="number"
            min="1"
            value={gameId}
            onChange={(event) => setGameId(event.target.value)}
          />
        </label>
        <button type="submit">입장</button>
      </form>
    </div>
  );
}
