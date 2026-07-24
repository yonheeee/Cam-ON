import { useCallback, useState } from 'react';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import { VideoPresets, type RoomOptions } from 'livekit-client';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GestureBoard } from '../../gesture/components/GestureBoard';
import { NinjaGamePanel } from '../../ninja/components/NinjaGamePanel';
import { RoomLobby } from '../../room/components/RoomLobby';
import { ninjaApi, NinjaApiError } from '../../ninja/api/ninjaApi';
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

// 지금 실제로 구현된 게임은 닌자뿐이라 gameId를 고정한다 — 코스에서 게임을 고르는 흐름이
// 생기면 그쪽에서 받아오도록 교체.
const NINJA_GAME_ID = 1;
const DEFAULT_TOTAL_ROUNDS = 5;

interface VideoCallRoomProps {
  // 방 생성/입장(RoomGate)까지 마치고 들어오는 화면이라, 여기 도달한 시점엔 넷 다 이미 확보돼 있다.
  accessToken: string;
  token: string;
  roomId: string;
  participantId: string;
}

export function VideoCallRoom({ accessToken, token, roomId, participantId }: VideoCallRoomProps) {
  // 게임이 실제로 열려 있는지(NinjaGamePanel이 폴링으로 판단)에 따라 대기방/게임 화면을 전환한다.
  // 손 인식(GesturePanel/GestureBoard)은 게임 중에만 켜서, 대기방에선 비디오/닉네임/준비/방장만 보이게 한다.
  const [gameActive, setGameActive] = useState(false);
  const [seeding, setSeeding] = useState(false);
  const [seedError, setSeedError] = useState<string | null>(null);
  const [connectionError, setConnectionError] = useState<string | null>(null);

  // 게임 시작 트리거(대기방→게임 자동시작 도메인이 아직 없어서 임시로 프론트가 seed를 호출).
  const startGame = useCallback(
    async (participantTokens: string[]) => {
      setSeeding(true);
      setSeedError(null);
      try {
        await ninjaApi.seed(roomId, NINJA_GAME_ID, participantTokens, DEFAULT_TOTAL_ROUNDS, accessToken);
        // 세션이 열리면 NinjaGamePanel 폴링이 이를 감지해 onActiveChange(true)로 게임 화면으로 전환된다.
      } catch (err) {
        setSeedError(err instanceof NinjaApiError ? err.message : '게임 시작 실패');
      } finally {
        setSeeding(false);
      }
    },
    [roomId, accessToken],
  );

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
        onError={(err) => setConnectionError(err.message)}
      >
        <VideoConference />
        {gameActive && <GesturePanel />}
        {gameActive && <GestureBoard />}
        {!gameActive && (
          <RoomLobby
            roomId={roomId}
            accessToken={accessToken}
            participantId={participantId}
            onStartGame={startGame}
            starting={seeding}
            startError={seedError}
          />
        )}
        <NinjaGamePanel
          roomId={roomId}
          gameId={NINJA_GAME_ID}
          accessToken={accessToken}
          onActiveChange={setGameActive}
        />
      </LiveKitRoom>
    </>
  );
}
