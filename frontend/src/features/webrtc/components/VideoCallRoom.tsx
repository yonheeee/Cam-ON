import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { LiveKitRoom, VideoConference, useConnectionState } from '@livekit/components-react';
import { ConnectionState, VideoPresets, type RoomOptions } from 'livekit-client';
import { CharadesMicrophoneController } from '../../charades/components/CharadesMicrophoneController';
import { CharadesGamePanel } from '../../charades/components/CharadesGamePanel';
import { GesturePanel } from '../../gesture/components/GesturePanel';
import { GestureBoard } from '../../gesture/components/GestureBoard';
import { NinjaGamePanel } from '../../ninja/components/NinjaGamePanel';
import { useRoomGameStarted } from '../../ninja/hooks/useRoomGameStarted';
import { LobbyScreen } from '../../room/components/LobbyScreen';
import { useRoomChat } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useRoomHeartbeat } from '../../room/hooks/useRoomHeartbeat';
import { clearRoom } from '../../room/lib/roomStorage';
import { FetchObjectGame } from '../../fetch/components/FetchObjectGame';
import { useFetchGame } from '../../fetch/hooks/useFetchGame';
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

// gameId/라운드 수 고정 — 코스에서 게임을 고르는 흐름이 생기면 그쪽에서 받아오도록 교체.
const NINJA_GAME_ID = 1;
const DEFAULT_TOTAL_ROUNDS = 5;
// 몸으로 말해요는 단일 라운드 정책(참가자 전원이 한 번씩 표현하면 게임 종료)이라 항상 1.
const CHARADES_GAME_ID = 2;
const CHARADES_TOTAL_ROUNDS = 1;

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
    // 서버에 자발적 퇴장을 즉시 알린다 — 이게 없으면 백엔드는 하트비트 만료(15초)로만 퇴장을
    // 감지하고, 방장이 나간 방은 그동안 방장 없이 참가자만 남아 게임을 시작할 수 없다.
    // 실패해도 하트비트 스윕이 뒷정리를 하므로 화면 전환은 막지 않는다.
    void roomApi.leaveRoom(roomId, accessToken).catch(() => {});
    clearRoom();
    navigate('/', { replace: true });
  }, [navigate, roomId, accessToken]);

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
  // 대기방↔게임 화면 전환. 닌자 진입은 game:started 이벤트 기준이고,
  // 손 인식(GesturePanel/GestureBoard)은 닌자 게임 중에만 켠다.
  const [gameActive, setGameActive] = useState(false);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const [isCharadesPresenter, setIsCharadesPresenter] = useState(false);
  // 어떤 게임이 열렸는지 — game:started payload의 gameId가 유일한 출처다. 아래 복구 경로(방 status가
  // PLAYING인데 이벤트를 놓친 경우)에서는 알 수 없어 null로 남고, 그때는 기존대로 닌자 화면을 띄운다.
  // ponytail: 방 스냅샷에 진행 중 gameId가 없어서 그렇다 — 스냅샷에 gameId가 추가되면 여기서 복원할 것.
  const [activeGameId, setActiveGameId] = useState<number | null>(null);
  const { messages, sendMessage } = useRoomChat();

  // 물건 가져오기 게임 상태 (LiveKit 데이터 채널 mock — Spring course/mission API 확정 전 임시).
  // ⚠ 로비의 "게임 시작" 버튼은 정식 백엔드 플로우(닌자)를 부르고, 물건 가져오기는
  //   /dev/fetch 진입로로만 시작한다 — 코스 도메인(게임 선택/순서)이 생기면 그쪽으로 통합.
  const fetchGame = useFetchGame();
  const fetchActive = fetchGame.state.phase !== 'idle';

  // [개발 전용] /dev/fetch로 들어오면(?autostart=fetch) LiveKit 연결 완료 시 게임을 자동 시작 —
  // 랜딩부터 클릭해 들어오는 번거로움 없이 게임 화면을 바로 확인하기 위함.
  const [searchParams] = useSearchParams();
  const connectionState = useConnectionState();
  const autoStartedRef = useRef(false);
  useEffect(() => {
    if (autoStartedRef.current) return;
    if (searchParams.get('autostart') !== 'fetch') return;
    if (connectionState !== ConnectionState.Connected) return;
    autoStartedRef.current = true;
    // ?target=휴대폰 이 붙어 있으면 제시어 고정 (물건 없는 개발 환경용), 없으면 랜덤
    void fetchGame.startGame(undefined, searchParams.get('target') ?? undefined);
  }, [searchParams, connectionState, fetchGame.startGame]);

  // 게임 진입은 game:started 이벤트로 한다(폴링 아님) — 방에 연결된 모든 클라이언트가 브로드캐스트를
  // 동시에 받아 함께 게임 화면으로 전환된다. 폴링에 의존하던 이전 방식은 일부 참가자가 전환을
  // 놓치는 문제가 있었다.
  useRoomGameStarted(roomId, accessToken, (payload) => {
    setActiveGameId(payload.gameId);
    setGameActive(true);
  });
  // 이벤트를 놓친 경우(늦은 접속/재접속) 방 status로 복구한다 — PLAYING이면 이미 시작된 게임이다.
  useEffect(() => {
    let cancelled = false;
    roomApi
      .getRoom(roomId, accessToken)
      .then((room) => {
        if (!cancelled && room.status === 'PLAYING') setGameActive(true);
      })
      .catch(() => {
        // 조회 실패는 무시 — game:started 이벤트가 주 경로다.
      });
    return () => {
      cancelled = true;
    };
  }, [roomId, accessToken]);

  // 게임 시작 트리거(정식). 방장이 누르면 서버가 방장 여부·전원 준비를 검증하고 방을 PLAYING으로
  // 전환한 뒤 세션을 연다. 참가자 토큰은 서버가 방의 실제 참가자 목록에서 만들므로 넘기지 않는다.
  // [개발 전용] 코스(게임 선택/순서) 도메인이 아직 없어서 어떤 게임을 시작할지 고를 방법이 없다.
  // /dev/charades로 들어오면 ?autostart=charades가 붙어 있어 닌자 대신 몸으로 말해요를 시작한다.
  // 코스 흐름이 생기면 이 분기와 /dev/* 라우트를 함께 지우고 코스가 정한 gameId를 넘기면 된다.
  // (searchParams는 위 fetch autostart 훅에서 선언한 것을 재사용)
  const startCharades = searchParams.get('autostart') === 'charades';

  const startGame = useCallback(async () => {
    setStarting(true);
    setStartError(null);
    try {
      await roomApi.startGame(
        roomId,
        startCharades ? CHARADES_GAME_ID : NINJA_GAME_ID,
        startCharades ? CHARADES_TOTAL_ROUNDS : DEFAULT_TOTAL_ROUNDS,
        accessToken,
      );
      // 화면 전환은 서버가 브로드캐스트하는 game:started 이벤트로 이뤄진다(방장 본인 포함 전원).
    } catch (err) {
      setStartError(err instanceof RoomApiError ? err.message : '게임 시작 실패');
    } finally {
      setStarting(false);
    }
  }, [roomId, accessToken, startCharades]);

  return (
    <>
      <CharadesMicrophoneController
        roomId={roomId}
        accessToken={accessToken}
        participantId={participantId}
        onPresenterChange={setIsCharadesPresenter}
      />
      {!gameActive && !fetchActive && (
        <LobbyScreen
          roomId={roomId}
          accessToken={accessToken}
          participantId={participantId}
          onStartGame={() => void startGame()}
          starting={starting}
          startError={startError}
          onLeave={onLeave}
          chatMessages={messages}
          onSendChat={sendMessage}
        />
      )}
      {fetchActive && (
        <FetchObjectGame
          state={fetchGame.state}
          myNickname={fetchGame.myNickname}
          onReportSuccess={fetchGame.reportSuccess}
          onEndRound={fetchGame.endRound}
          onNextRound={() => void fetchGame.nextRound()}
          onExit={fetchGame.exitGame}
          onLeave={onLeave}
        />
      )}
      {/* 몸으로 말해요는 자체 전체화면(.charades-screen)에 캠 타일·정답 채팅까지 다 그리므로
          VideoConference 그리드/손동작 패널을 띄우지 않는다. 표현자 마이크 음소거는 위
          CharadesMicrophoneController가 계속 담당한다. */}
      {gameActive && activeGameId === CHARADES_GAME_ID && (
        <CharadesGamePanel
          roomId={roomId}
          gameId={CHARADES_GAME_ID}
          accessToken={accessToken}
          onActiveChange={setGameActive}
        />
      )}
      {gameActive && activeGameId !== CHARADES_GAME_ID && (
        <>
          <VideoConference
            className={
              isCharadesPresenter
                ? 'lk-video-conference video-call-room__charades-presenter'
                : 'lk-video-conference'
            }
          />
          <GesturePanel />
          <GestureBoard />
          {/* 닌자 게임 중엔 채팅 창을 띄우지 않는다(손동작 게임이라 불필요). */}
          {/* 게임 중에만 마운트 — 대기방에선 ninja state 폴링을 아예 돌리지 않는다(불필요한
              NINJA_SESSION_NOT_FOUND 요청 제거). 세션이 사라지면 onActiveChange(false)로 대기방 복귀. */}
          <NinjaGamePanel
            roomId={roomId}
            gameId={NINJA_GAME_ID}
            accessToken={accessToken}
            onActiveChange={setGameActive}
          />
        </>
      )}
    </>
  );
}
