import { useCallback, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { LiveKitRoom, VideoConference } from '@livekit/components-react';
import { VideoPresets, type RoomOptions } from 'livekit-client';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GestureBoard } from '../../gesture/components/GestureBoard';
import { NinjaGamePanel } from '../../ninja/components/NinjaGamePanel';
import { LobbyScreen } from '../../room/components/LobbyScreen';
import { ChatPanel } from '../../chat/components/ChatPanel';
import { useRoomChat } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useRoomHeartbeat } from '../../room/hooks/useRoomHeartbeat';
import { clearRoom } from '../../room/lib/roomStorage';
import { roomApi, RoomApiError } from '../../room/api/roomApi';
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
  // 방 생성/입장 플로우를 마치고 들어오는 화면이라, 여기 도달한 시점엔 넷 다 이미 확보돼 있다.
  accessToken: string;
  token: string;
  roomId: string;
  participantId: string;
}

export function VideoCallRoom({ accessToken, token, roomId, participantId }: VideoCallRoomProps) {
  const navigate = useNavigate();
  // 방에 머무는 내내 하트비트를 보내 백엔드의 연결 가드(TTL 15초)에 의해 방에서 제거되지 않게 한다.
  useRoomHeartbeat(roomId, accessToken);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  // 사용자가 스스로 나간 것(확인 팝업 경유)과 예기치 못한 종료를 구분한다 —
  // 스스로 나가면 바로 메인으로, 예기치 못한 종료면 "방 종료" 팝업(피그마 방 종료 프레임)을 띄운다.
  const leavingRef = useRef(false);
  const [closed, setClosed] = useState(false);

  const leaveRoom = useCallback(() => {
    leavingRef.current = true;
    clearRoom();
    navigate('/', { replace: true });
  }, [navigate]);

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
        onDisconnected={() => {
          if (leavingRef.current) return; // 의도한 퇴장은 leaveRoom이 정리까지 끝냄
          clearRoom();
          setClosed(true);
        }}
        onError={(err) => setConnectionError(err.message)}
      >
        <RoomContent
          roomId={roomId}
          accessToken={accessToken}
          participantId={participantId}
          onLeave={leaveRoom}
        />
      </LiveKitRoom>
      {closed && (
        <PixelConfirmModal
          title="방 연결이 종료되었어요"
          message="방이 닫혔거나 연결이 끊어졌습니다."
          confirmLabel="메인으로"
          onConfirm={() => navigate('/', { replace: true })}
        />
      )}
    </>
  );
}

interface RoomContentProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  onLeave: () => void;
}

// LiveKitRoom 컨텍스트 안에서 동작하는 부분 — 대기방(LobbyScreen) ↔ 게임 화면을 전환한다.
// 채팅 상태는 여기(useRoomChat)가 소유해서 화면 전환으로 패널이 리마운트돼도 내역이 유지된다.
function RoomContent({ roomId, accessToken, participantId, onLeave }: RoomContentProps) {
  // 게임이 실제로 열려 있는지(NinjaGamePanel이 폴링으로 판단)에 따라 대기방/게임 화면을 전환한다.
  // 손 인식(GesturePanel/GestureBoard)은 게임 중에만 켠다.
  const [gameActive, setGameActive] = useState(false);
  const [seeding, setSeeding] = useState(false);
  const [seedError, setSeedError] = useState<string | null>(null);
  const { messages, sendMessage } = useRoomChat();

  // 게임 시작 트리거. 방장이 누르면 서버가 방장 여부·전원 준비를 검증하고 방을 PLAYING으로
  // 전환한 뒤 세션을 연다. 참가자 토큰은 서버가 방의 실제 참가자 목록에서 만들므로 넘기지 않는다.
  const startGame = useCallback(
    async () => {
      setSeeding(true);
      setSeedError(null);
      try {
        await roomApi.startGame(roomId, NINJA_GAME_ID, DEFAULT_TOTAL_ROUNDS, accessToken);
        // 세션이 열리면 NinjaGamePanel 폴링이 이를 감지해 onActiveChange(true)로 게임 화면으로 전환된다.
      } catch (err) {
        setSeedError(err instanceof RoomApiError ? err.message : '게임 시작 실패');
      } finally {
        setSeeding(false);
      }
    },
    [roomId, accessToken],
  );

  return (
    <>
      {!gameActive && (
        <LobbyScreen
          roomId={roomId}
          accessToken={accessToken}
          participantId={participantId}
          onStartGame={startGame}
          starting={seeding}
          startError={seedError}
          onLeave={onLeave}
          chatMessages={messages}
          onSendChat={sendMessage}
        />
      )}
      {gameActive && (
        <>
          <VideoConference />
          <GesturePanel />
          <GestureBoard />
          <ChatPanel variant="floating" messages={messages} onSend={sendMessage} />
        </>
      )}
      <NinjaGamePanel
        roomId={roomId}
        gameId={NINJA_GAME_ID}
        accessToken={accessToken}
        onActiveChange={setGameActive}
      />
    </>
  );
}
