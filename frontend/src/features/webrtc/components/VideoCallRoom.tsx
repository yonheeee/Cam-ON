import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { LiveKitRoom, useParticipants } from '@livekit/components-react';
import { VideoPresets, type RoomOptions } from 'livekit-client';
import { CharadesMicrophoneController } from '../../charades/components/CharadesMicrophoneController';
import { CharadesGamePanel } from '../../charades/components/CharadesGamePanel';
import { NinjaBattleScreen } from '../../ninja/components/NinjaBattleScreen';
import { CourseResultScreen } from '../../course/components/CourseResultScreen';
import { SetResultScreen } from '../../course/components/SetResultScreen';
import { IntermissionScreen } from '../../course/components/IntermissionScreen';
import {
  courseApi,
  CourseApiError,
  GAME_LABELS,
  type CourseItem,
} from '../../course/api/courseApi';
import { useCourseProgress, type GameStartedData } from '../../course/hooks/useCourseProgress';
import { useSetResult } from '../../course/hooks/useSetResult';
import { useGameCatalog } from '../../course/hooks/useGameCatalog';
import { LobbyScreen } from '../../room/components/LobbyScreen';
import { useRoomChat } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useRoomHeartbeat } from '../../room/hooks/useRoomHeartbeat';
import { clearRoom } from '../../room/lib/roomStorage';
import { FetchCoursePanel } from '../../fetch/components/FetchCoursePanel';
import { roomApi, RoomApiError, type ParticipantResponse } from '../../room/api/roomApi';
import { analyticsApi } from '../../analytics/api/analyticsApi';
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

// LiveKit Cloud 프로젝트 서버 URL. 백엔드가 토큰을 서명할 때 쓰는 프로젝트(backend/.env의
// LIVEKIT_*)와 반드시 같은 프로젝트여야 한다 — 어긋나면 토큰 서명은 정상인데 연결만 거부돼서
// 원인을 찾기 어렵다. 키를 새로 발급받는 일이 반복되므로 코드에 박지 않고 frontend/.env에서 읽는다
// (기본값은 커밋된 .env, 개인 환경만 다르게 하려면 .env.local에서 덮어쓴다).
const LIVEKIT_SERVER_URL = import.meta.env.VITE_LIVEKIT_URL ?? '';

// 세트가 끝나고 다음 게임 룰 설명 구간이 열리기까지의 시간(CourseRunner.SET_RESULT_DURATION).
// 중간 결과 화면 카운트다운 기준 — 백엔드 값이 바뀌면 같이 고친다.
// 그 뒤 룰 설명 구간(SESSION_INTERMISSION)이 이어지고 나서 다음 게임이 열린다.
const SET_RESULT_DURATION_MS = 8000;

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
  // 설정이 비어 있으면 빈 URL로 연결을 시도하게 되고, LiveKit이 주는 메시지로는 원인을 알 수
  // 없다(단순 연결 실패로 보인다). 그래서 무엇이 빠졌는지 여기서 직접 짚어준다.
  const [connectionError, setConnectionError] = useState<string | null>(
    LIVEKIT_SERVER_URL
      ? null
      : 'VITE_LIVEKIT_URL이 설정되지 않았습니다 — frontend/.env를 확인하세요.',
  );
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

  // 창을 그냥 닫거나 다른 사이트로 이동해도 퇴장을 알린다. 이게 없으면 서버는 하트비트 만료
  // (TTL 15초)로만 이탈을 알 수 있고, 그 15초 동안 participants 집합에 자리가 남아 있어서
  // 정원이 찬 것으로 판정된다 — 나간 사람 자리에 아무도 못 들어오고 재입장도 ROOM_FULL이 된다.
  //
  // pagehide만 쓴다. visibilitychange(hidden)는 탭을 잠깐 전환하거나 화면을 끌 때도 발생해서
  // 멀쩡히 방에 있는 사람을 내보내게 된다. beforeunload는 모바일에서 발생이 보장되지 않는데
  // pagehide는 그 경로까지 덮는다.
  useEffect(() => {
    const handlePageHide = () => {
      // 나가기 버튼으로 이미 퇴장을 보낸 경우엔 중복 요청을 보내지 않는다.
      if (leavingRef.current) return;
      roomApi.leaveRoomOnUnload(roomId, accessToken);
    };
    window.addEventListener('pagehide', handlePageHide);
    return () => window.removeEventListener('pagehide', handlePageHide);
  }, [roomId, accessToken]);

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

  const { activeSession, finished, intermission, skipped, clearSkipped } = useCourseProgress(
    roomId,
    accessToken,
    participantId,
  );
  const recordedRoomEntryRef = useRef(false);
  useEffect(() => {
    if (recordedRoomEntryRef.current) return;
    recordedRoomEntryRef.current = true;
    void analyticsApi
      .recordEvent(roomId, accessToken, {
        eventName: 'ROOM_ENTERED',
      })
      .catch(() => {
        // Analytics must never block or interrupt room entry.
      });
  }, [roomId, accessToken]);

  const recordedResultIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!finished) {
      recordedResultIdRef.current = null;
      return;
    }
    const resultId = `${finished.totalSessions}:${finished.ranking
      .map((entry) => `${entry.participantId}:${entry.totalScore}`)
      .join(',')}`;
    if (recordedResultIdRef.current === resultId) return;
    recordedResultIdRef.current = resultId;
    void analyticsApi
      .recordEvent(roomId, accessToken, {
        eventName: 'RESULT_SCREEN_VIEWED',
        properties: { totalSessions: finished.totalSessions },
      })
      .catch(() => {
        // Analytics must never block or interrupt the game result screen.
      });
  }, [finished, roomId, accessToken]);

  // 이벤트로 받은 세션이 우선이고, 놓친 경우(늦은 접속/재접속)엔 아래 복구 경로가 채운다.
  const [recoveredSession, setRecoveredSession] = useState<GameStartedData | null>(null);
  const session = activeSession ?? recoveredSession;
  // 종합 결과 payload에는 participantId만 있어서 이름을 붙이려면 방 스냅샷이 필요하다.
  const [participants, setParticipants] = useState<ParticipantResponse[]>([]);
  // 중간 결과의 "다음 세트가 무슨 게임인지"와 "SET n / 총 세트"를 그리려면 코스 구성이 필요하다.
  const [courseItems, setCourseItems] = useState<CourseItem[]>([]);
  // 인터미션의 "바로 시작"은 방장 전용 — 게임 도중 방장이 바뀔 수 있어(연쇄 위임) 스냅샷을
  // 새로 읽어 갱신한다. (결과 화면의 대기방 복귀는 이제 전원이 각자 누르므로 방장 여부를 안 본다)
  const [hostParticipantId, setHostParticipantId] = useState<string | null>(null);
  // 한 번 본 닉네임은 잊지 않고 쌓아 둔다. 방 스냅샷에는 "지금 방에 있는 사람"만 들어 있는데,
  // 게임 도중 나간 사람도 그 세트의 순위표에는 (대개 0점으로) 남아 있어서 스냅샷만 보면
  // "알 수 없음 0점" 줄이 생긴다. LiveKit 참가자 이름도 같이 넣어 늦게 들어온 사람까지 덮는다.
  const livekitParticipants = useParticipants();
  const [nicknameById, setNicknameById] = useState<Map<string, string>>(new Map());
  useEffect(() => {
    setNicknameById((prev) => {
      let next: Map<string, string> | null = null;
      const upsert = (id: string, nickname: string | undefined) => {
        if (!nickname || prev.get(id) === nickname) return;
        next ??= new Map(prev);
        next.set(id, nickname);
      };
      for (const participant of participants) {
        upsert(participant.participantId, participant.nickname);
      }
      for (const participant of livekitParticipants) {
        upsert(participant.identity, participant.name);
      }
      // 바뀐 게 없으면 같은 Map을 돌려줘 불필요한 리렌더를 막는다.
      return next ?? prev;
    });
  }, [participants, livekitParticipants]);

  // 새 게임이 열리면 인터미션 표시를 내린다.
  useEffect(() => {
    if (activeSession) {
      setBetweenGames(false);
      setRecoveredSession(null);
    }
  }, [activeSession]);

  // 코스가 연 실제 물건 가져오기는 아래 FetchCoursePanel(서버 주도, useFetchRound)이 담당한다.
  // 랜딩부터 클릭해 들어오는 번거로움 없이 게임 화면을 바로 확인하기 위함.
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
        setHostParticipantId(room.hostParticipantId);
        setCourseItems(course.items);
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

  // 세트 하나가 끝나면(게임별 game-ended) 중간 결과 화면을 띄운다. 다음 세트가 열리면(game:started)
  // 훅이 스스로 null로 돌아가며 화면이 접힌다.
  const finishedSet = useSetResult(roomId, accessToken);
  const [advancing, setAdvancing] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  useEffect(() => {
    setAdvancing(false);
    if (!finishedSet) {
      setSecondsLeft(null);
      return;
    }
    const deadline = finishedSet.endedAt + SET_RESULT_DURATION_MS;
    const tick = () => setSecondsLeft(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
    tick();
    const timer = setInterval(tick, 500);
    return () => clearInterval(timer);
  }, [finishedSet]);

  // [방장 전용] 기다리지 않고 바로 다음 세트로. 세트 결과 구간과 룰 설명 구간을 한 번에 건너뛴다.
  //
  // 인터미션 "바로 시작"과 같은 엔드포인트를 쓴다 — 하는 일이 같고(대기를 건너뛰고 다음 게임을
  // 연다), 서버가 "어느 대기를 건너뛰려는지"를 seq로 확인하는 가드도 그대로 필요하다. 이 브랜치가
  // 원래 부르던 /api/rooms/{roomId}/course/next는 백엔드에 없어서 항상 404였다.
  const startNextSet = useCallback(async () => {
    if (!finishedSet) return;
    setAdvancing(true);
    try {
      await courseApi.skipIntermission(roomId, finishedSet.sessionSeq, accessToken);
      // 화면 전환은 game:started가 담당한다 — 그때까지 버튼은 "준비 중..."으로 둔다.
    } catch {
      setAdvancing(false);
    }
  }, [roomId, accessToken, finishedSet]);

  const [returning, setReturning] = useState(false);
  const [returnError, setReturnError] = useState<string | null>(null);

  // 종합 결과가 뜨는 순간, 그리고 인터미션이 시작될 때 참가자 스냅샷을 다시 읽는다 —
  // 결과 화면은 최신 닉네임 표가 필요하고(payload에는 participantId만 있다), 인터미션은
  // "바로 시작" 버튼을 지금의 방장에게만 띄우기 위해 최신 방장 id가 필요하다(게임 도중
  // 연쇄 위임으로 바뀔 수 있다).
  // 세 화면 모두 최신 방장 id가 필요하다: 종합 결과(닉네임 표), 세트 중간 결과("다음 세트
  // 시작하기"가 방장 전용), 인터미션("바로 시작"이 방장 전용).
  const snapshotTrigger = finished
    ? 'finished'
    : finishedSet
      ? `set:${finishedSet.sessionSeq}`
      : intermission
        ? 'intermission'
        : null;
  useEffect(() => {
    if (!snapshotTrigger) return;
    let cancelled = false;
    // 코스도 같이 다시 읽는다 — 마운트 시점(방 생성 직후)엔 코스가 비어 있고 대기방에서 짜므로,
    // 한 번만 읽으면 "다음 세트"를 못 찾아 마지막 세트인 것처럼 보인다.
    Promise.all([
      roomApi.getRoom(roomId, accessToken),
      courseApi.getCourse(roomId, accessToken),
    ])
      .then(([room, course]) => {
        if (cancelled) return;
        setParticipants(room.participants);
        setHostParticipantId(room.hostParticipantId);
        setCourseItems(course.items);
      })
      .catch(() => {
        // 실패해도 마운트 시점 스냅샷으로 그린다.
      });
    return () => {
      cancelled = true;
    };
  }, [snapshotTrigger, roomId, accessToken]);

  // 결과 화면이 뜨거나 접힐 때 복귀 요청 상태를 초기화 — 두 번째 코스의 결과에서
  // 이전 코스의 "돌아가는 중..."이 남아 버튼이 죽은 것처럼 보이지 않게.
  useEffect(() => {
    setReturning(false);
    setReturnError(null);
  }, [finished]);

  // 종합 결과 → 대기방 복귀. 방장 전용이 아니라 전원이 각자 누른다. 성공 시 화면 전환은 서버가
  // 쏘는 course:member-returned가 내 id로 돌아올 때 이뤄지므로 여기선 요청만 보낸다 — 남이
  // 눌러도 내 화면은 그대로다(한 번에 전원이 들어오지 않는다).
  const returnToLobby = useCallback(async () => {
    setReturning(true);
    setReturnError(null);
    try {
      await roomApi.returnToLobby(roomId, accessToken);
    } catch (err) {
      setReturnError(err instanceof RoomApiError ? err.message : '대기방 복귀 실패');
      setReturning(false);
    }
    // 성공 시 returning을 유지한다 — 내 복귀 이벤트가 오면 이 화면 자체가 사라진다.
  }, [roomId, accessToken]);

  const [skipping, setSkipping] = useState(false);
  const [skipError, setSkipError] = useState<string | null>(null);

  // 인터미션이 바뀌면(다음 게임 사이로 넘어감) 스킵 요청 상태를 초기화 — 지난 인터미션의
  // "시작하는 중..."이 남아 버튼이 죽은 것처럼 보이지 않게.
  useEffect(() => {
    setSkipping(false);
    setSkipError(null);
  }, [intermission?.finishedSessionSeq]);

  // [방장 전용] 남은 대기를 건너뛴다. 어느 인터미션인지 서버가 특정할 수 있도록 받은
  // finishedSessionSeq를 그대로 돌려보낸다. 화면 전환은 평소와 같은 game:started가 담당한다.
  const skipIntermission = useCallback(async () => {
    if (!intermission) return;
    setSkipping(true);
    setSkipError(null);
    try {
      await courseApi.skipIntermission(roomId, intermission.finishedSessionSeq, accessToken);
    } catch (err) {
      setSkipError(err instanceof CourseApiError ? err.message : '바로 시작 실패');
      setSkipping(false);
    }
  }, [roomId, accessToken, intermission]);

  const activeGameName = gameNameOf(session?.gameId);
  const inGame = !!session && !finished;
  // 중간 결과에 띄울 "다음 세트" 게임. 마지막 세트였으면 없다(그땐 곧 종합 결과가 온다).
  const nextItem = finishedSet
    ? courseItems.find((item) => item.idx === finishedSet.sessionSeq + 1)
    : undefined;
  // 게임이 열리기 전 대기(코스 첫 게임 앞) 또는 게임 사이 대기. 이 동안엔 대기방을 그리지 않고
  // 룰 설명 화면이 자리를 차지한다 — 첫 게임 앞에는 아직 열린 세션이 없어(inGame=false) 이
  // 조건이 없으면 대기방이 그대로 보인다.
  const showIntermission = !!intermission && !finished;
  // 세트 중간 결과는 룰 설명이 오기 전까지만 보여준다. 서버가 두 구간을 순서대로 주므로
  // (세트 결과 8초 → course:intermission 도착 → 룰 설명 8초) 그 이벤트가 곧 인계 신호다 —
  // 프론트에서 따로 타이머를 재면 사람마다 전환 시점이 어긋난다.
  const showSetResult = !!finishedSet && !finished && !intermission;

  return (
    <>
      <CharadesMicrophoneController
        roomId={roomId}
        accessToken={accessToken}
        participantId={participantId}
        onPresenterChange={setIsCharadesPresenter}
      />
      {/* develop이 /dev/fetch 목업(fetchActive)을 제거했으므로 그 조건은 빠졌다.
          showIntermission은 룰 설명 화면이 대기방 대신 자리를 차지하게 하는 조건이다. */}
      {!inGame && !finished && !showIntermission && (
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

      {/* 몸으로 말해요는 자체 전체화면(.charades-screen)에 캠 타일·정답 채팅까지 다 그리므로
          VideoConference 그리드/손동작 패널을 띄우지 않는다. 표현자 마이크 음소거는 위
          CharadesMicrophoneController가 계속 담당한다. */}
      {inGame && session && activeGameName === 'CHARADES' && (
        <CharadesGamePanel
          roomId={roomId}
          gameId={session.gameId}
          accessToken={accessToken}
          onActiveChange={(active) => setBetweenGames(!active)}
          onLeave={onLeave}
        />
      )}
      {/* 코스가 연 물건 가져오기 — 서버 주도 진행(round:start/end를 STOMP로 수신). */}
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
          onLeave={onLeave}
        />
      )}

      {/* 세트 중간 결과 — 게임 하나가 끝나고 룰 설명 구간이 열리기 전까지 */}
      {showSetResult && finishedSet && (
        <SetResultScreen
          setIndex={finishedSet.sessionSeq}
          totalSets={courseItems.length || finishedSet.sessionSeq}
          setResult={finishedSet.setResult}
          courseRanking={finishedSet.courseRanking}
          nicknameById={nicknameById}
          nextGameLabel={nextItem ? GAME_LABELS[nextItem.gameName] : null}
          participantId={participantId}
          isHost={hostParticipantId === participantId}
          secondsLeft={secondsLeft}
          onNext={() => void startNextSet()}
          starting={advancing}
        />
      )}

      {/* 게임이 열리기 전 대기 — 코스 첫 게임 앞이든 게임 사이든 같은 화면을 쓴다.
          다음 게임 룰 설명(서버가 MySQL games.description에서 읽어 보낸 값)과 방장용
          "바로 시작"이 들어 있다. 게임 사이에서는 세트 결과 구간이 끝난 뒤에 온다. */}
      {showIntermission && intermission && (
        <IntermissionScreen
          intermission={intermission}
          isHost={hostParticipantId === participantId}
          onSkip={() => void skipIntermission()}
          skipping={skipping}
          skipError={skipError}
        />
      )}
      {/* course:intermission을 놓친 클라이언트(늦은 접속 등)용 폴백 — 게임 패널이 자기 종료
          화면을 접은 뒤 빈 화면이 보이는 것만 막는다. 중간 결과가 떠 있으면 그쪽이 덮는다. */}
      {inGame && betweenGames && !showIntermission && !finishedSet && (
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
          onReturnToLobby={() => void returnToLobby()}
          returning={returning}
          returnError={returnError}
          onLeave={onLeave}
        />
      )}
    </>
  );
}
