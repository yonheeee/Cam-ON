import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { LiveKitRoom, useConnectionState } from '@livekit/components-react';
import { ConnectionState, VideoPresets, type RoomOptions } from 'livekit-client';
import { CharadesMicrophoneController } from '../../charades/components/CharadesMicrophoneController';
import { CharadesGamePanel } from '../../charades/components/CharadesGamePanel';
import { NinjaBattleScreen } from '../../ninja/components/NinjaBattleScreen';
import { CourseResultScreen } from '../../course/components/CourseResultScreen';
import { courseApi } from '../../course/api/courseApi';
import { useCourseProgress, type GameStartedData } from '../../course/hooks/useCourseProgress';
import { useGameCatalog } from '../../course/hooks/useGameCatalog';
import { LobbyScreen } from '../../room/components/LobbyScreen';
import { useRoomChat } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useRoomHeartbeat } from '../../room/hooks/useRoomHeartbeat';
import { clearRoom } from '../../room/lib/roomStorage';
import { FetchObjectGame } from '../../fetch/components/FetchObjectGame';
import { FetchCoursePanel } from '../../fetch/components/FetchCoursePanel';
import { useFetchGame } from '../../fetch/hooks/useFetchGame';
import { roomApi, RoomApiError, type ParticipantResponse } from '../../room/api/roomApi';
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

// LiveKitRoom 컨텍스트 안에서 동작하는 부분 — 대기방(LobbyScreen) ↔ 코스의 게임들 ↔ 종합 결과를
// 전환한다. 채팅 상태는 여기(useRoomChat)가 소유해서 화면 전환으로 패널이 리마운트돼도 내역이 유지된다.
//
// 무엇을 띄울지는 코스 진행 상태가 결정한다:
//   activeSession 없음 + 종료 아님 → 대기방
//   activeSession 있음            → 그 게임의 패널 (game:started의 gameId를 이름으로 바꿔 분기)
//   course:finished 도착          → 종합 결과
function RoomContent({ roomId, accessToken, participantId, onLeave }: RoomContentProps) {
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  // 값은 더 이상 읽지 않는다 — 표현자 강조는 CharadesGamePanel이 자체 화면에서 직접 처리하고,
  // 닌자도 전용 화면을 쓰게 되면서 이 플래그로 클래스를 갈아끼울 대상이 없어졌다.
  // 콜백 프로퍼티는 CharadesMicrophoneController가 요구하므로 setter만 남긴다.
  const [, setIsCharadesPresenter] = useState(false);
  // 게임 하나가 끝나고 다음 게임이 열리기 전까지의 구간. 패널이 자기 종료 화면을 접은 뒤
  // 빈 화면이 보이는 것을 막는다(서버가 이때 8초 인터미션을 준다).
  const [betweenGames, setBetweenGames] = useState(false);
  const { messages, sendMessage } = useRoomChat();
  const { gameNameOf } = useGameCatalog(accessToken);

  const { activeSession, finished, skipped, clearSkipped } = useCourseProgress(
    roomId,
    accessToken,
  );
  // 이벤트로 받은 세션이 우선이고, 놓친 경우(늦은 접속/재접속)엔 아래 복구 경로가 채운다.
  const [recoveredSession, setRecoveredSession] = useState<GameStartedData | null>(null);
  const session = activeSession ?? recoveredSession;
  // 종합 결과 payload에는 participantId만 있어서 이름을 붙이려면 방 스냅샷이 필요하다.
  const [participants, setParticipants] = useState<ParticipantResponse[]>([]);
  const nicknameById = useMemo(
    () => new Map(participants.map((p) => [p.participantId, p.nickname])),
    [participants],
  );

  // 새 게임이 열리면 인터미션 표시를 내린다.
  useEffect(() => {
    if (activeSession) {
      setBetweenGames(false);
      setRecoveredSession(null);
    }
  }, [activeSession]);

  // [개발 전용] /dev/fetch 진입로의 데이터채널 mock 상태 — 백엔드 없이 화면만 확인하는 용도.
  // 코스가 연 실제 물건 가져오기는 아래 FetchCoursePanel(서버 주도, useFetchRound)이 담당한다.
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

  // game:started를 놓친 클라이언트 복구: 방이 PLAYING이면 코스의 current_session_seq가 가리키는
  // 칸이 곧 지금 진행 중인 게임이다. (예전엔 진행 중 gameId를 알 방법이 없어 닌자로 고정했다.)
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      roomApi.getRoom(roomId, accessToken),
      courseApi.getCourse(roomId, accessToken),
    ])
      .then(([room, course]) => {
        if (cancelled) return;
        setParticipants(room.participants);
        if (room.status !== 'PLAYING') return;
        const current = course.items.find((item) => item.idx === course.currentSessionSeq);
        if (current) {
          setRecoveredSession({
            gameId: current.gameId,
            sessionSeq: current.idx,
            totalRounds: current.roundCount,
          });
        }
      })
      .catch(() => {
        // 조회 실패는 무시 — game:started 이벤트가 주 경로다.
      });
    return () => {
      cancelled = true;
    };
  }, [roomId, accessToken]);

  // 방장이 누르면 서버가 방장 여부·전원 준비·코스 유효성을 검증하고 코스의 첫 게임을 연다.
  // 무엇을 할지는 코스(세트 큐)에 이미 확정돼 있어 클라이언트가 보낼 것이 없다.
  const startGame = useCallback(async () => {
    setStarting(true);
    setStartError(null);
    try {
      await roomApi.startGame(roomId, accessToken);
      // 화면 전환은 서버가 브로드캐스트하는 game:started로 이뤄진다(방장 본인 포함 전원).
    } catch (err) {
      setStartError(err instanceof RoomApiError ? err.message : '게임 시작 실패');
    } finally {
      setStarting(false);
    }
  }, [roomId, accessToken]);

  const activeGameName = gameNameOf(session?.gameId);
  const inGame = !!session && !finished;

  return (
    <>
      <CharadesMicrophoneController
        roomId={roomId}
        accessToken={accessToken}
        participantId={participantId}
        onPresenterChange={setIsCharadesPresenter}
      />
      {!inGame && !finished && !fetchActive && (
        <LobbyScreen
          roomId={roomId}
          accessToken={accessToken}
          participantId={participantId}
          onStartGame={startGame}
          starting={starting}
          startError={startError}
          onLeave={onLeave}
          chatMessages={messages}
          onSendChat={sendMessage}
        />
      )}

      {/* [개발 전용] /dev/fetch 진입로로 시작한 물건 가져오기 mock 화면 — 코스 흐름과 무관 */}
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
      {inGame && session && activeGameName === 'CHARADES' && (
        <CharadesGamePanel
          roomId={roomId}
          gameId={session.gameId}
          accessToken={accessToken}
          onActiveChange={(active) => setBetweenGames(!active)}
        />
      )}
      {/* 코스가 연 물건 가져오기 — 서버 주도 진행(round:start/end를 STOMP로 수신).
          위의 fetchActive(/dev/fetch mock)와는 진입로가 다르다. */}
      {inGame && session && activeGameName === 'FETCH_OBJECT' && (
        <FetchCoursePanel
          roomId={roomId}
          gameId={session.gameId}
          accessToken={accessToken}
          nicknameById={nicknameById}
          onLeave={onLeave}
        />
      )}
      {/* 카탈로그 조회가 실패해 이름을 모를 때도 게임 화면은 띄워야 하므로 닌자를 기본으로 둔다. */}
      {/* 닌자도 몸으로말해요처럼 자체 전체화면에 캠 타일까지 직접 그린다 — HP/점수/공격권/이펙트를
          각 참가자 타일에 얹으려면 그리드 소유권이 게임 화면에 있어야 한다. 그래서 VideoConference와
          손동작 디버그 박스(GesturePanel/GestureBoard)를 따로 띄우지 않는다(인식 루프를 소유한
          GesturePanel은 NinjaBattleScreen 사이드바 안에서 마운트된다).
          닌자 게임 중엔 채팅 창도 띄우지 않는다(손동작 게임이라 불필요). */}
      {inGame && session && activeGameName !== 'CHARADES' && activeGameName !== 'FETCH_OBJECT' && (
        <NinjaBattleScreen
          roomId={roomId}
          gameId={session.gameId}
          accessToken={accessToken}
          onActiveChange={(active) => setBetweenGames(!active)}
        />
      )}

      {/* 게임과 게임 사이 — 서버가 다음 세션을 열 때까지의 빈 화면을 덮는다 */}
      {inGame && betweenGames && (
        <div className="video-call-room__intermission">
          <p className="pap-pixel-title">다음 게임을 준비하고 있어요...</p>
        </div>
      )}

      {/* 인원이 안 맞아 건너뛴 게임 안내 */}
      {skipped && (
        <PixelConfirmModal
          title="건너뛴 게임이 있어요"
          message={`${skipped.gameName ?? '게임'}은 지금 인원으로 진행할 수 없어 넘어갔어요.`}
          confirmLabel="확인"
          onConfirm={clearSkipped}
        />
      )}

      {finished && (
        <CourseResultScreen
          ranking={finished.ranking}
          totalSessions={finished.totalSessions}
          nicknameById={nicknameById}
          participantId={participantId}
          onLeave={onLeave}
        />
      )}
    </>
  );
}
