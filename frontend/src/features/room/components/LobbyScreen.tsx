import { useCallback, useEffect, useRef, useState } from 'react';
import { ParticipantTile, useLocalParticipant, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { ChatPanel } from '../../chat/components/ChatPanel';
import type { ChatMessage } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { roomApi, RoomApiError } from '../api/roomApi';
import { SettingsModal } from './SettingsModal';
import { StartPreflightModal } from './StartPreflightModal';
import { useRoomLobby } from '../hooks/useRoomLobby';
import { GameSetupScreen } from '../../course/components/GameSetupScreen';
import { GAME_LABELS } from '../../course/api/courseApi';
import { useCourse } from '../../course/hooks/useCourse';
import { useTopics } from '../../course/hooks/useTopics';
import {
  CamOffIcon,
  CamOnIcon,
  CheckCircleIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CopyIcon,
  CrownIcon,
  GearIcon,
  InResultIcon,
  KickIcon,
  LinkIcon,
  MicOffIcon,
  MicOnIcon,
  PlayIcon,
  SpinnerIcon,
} from './lobbyIcons';
import './LobbyScreen.css';

interface LobbyScreenProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  /** 코스는 서버가 읽으므로 인자가 없다 — 방장만 호출된다 */
  onStartGame: () => void;
  starting: boolean;
  startError: string | null;
  /** 사용자가 스스로 방을 나갈 때 (연결 정리 + 메인 이동은 상위가 처리) */
  onLeave: () => void;
  chatMessages: ChatMessage[];
  onSendChat: (text: string) => void;
}

// 카메라를 끈 채로 게임에 들어가면 되돌릴 방법이 없다 — 게임 화면에는 카메라 토글이 없고
// 대기방에만 있다. 그래서 캠이 꺼져 있으면 준비/시작을 막는다.
//
// 말풍선(data-hint)은 CSS가 nowrap이라 한 줄에 들어가는 길이여야 사이드바를 안 넘친다.
// 이유를 더 길게 설명하는 문구는 토스트로만 쓴다.
const CAMERA_OFF_HINT = '카메라를 켜야 준비할 수 있어요!';
const CAMERA_OFF_MESSAGE = '카메라를 켜야 준비할 수 있어요 — 게임 중엔 다시 켤 수 없어요!';
const CAMERA_OFF_READY_CLEARED = '카메라를 꺼서 준비가 취소됐어요.';
const CAMERA_OFF_HOST_MESSAGE = '카메라를 켜야 시작할 수 있어요!';

// 대기방 전체 화면 — Figma `03 · PIXEL ARCADE PLAZA · Screen Mockups` /
// `Screen / Lobby v2 · Standalone Cards`(1275:47)를 1:1로 옮긴 화면.
//
// 해상도 대응: 화면 전체를 축소/확대하지 않는다. 글자·외곽선·여백·카드 간격·사이드 열 폭(416)
// ·티켓/게임구성/하단버튼 높이는 고정하고, 남는 공간은 참가자 타일 그리드와 채팅 카드가 흡수한다.
// 1440×1024에서는 Figma와 픽셀 단위로 같고, 그 외 해상도에서도 스크롤 없이 한 화면에 들어온다.
// 참가자 정보(이름/역할/준비)는 비디오 타일 하단 바에, 사이드는 티켓 + 게임 구성 + 채팅 + 액션.
export function LobbyScreen({
  roomId,
  accessToken,
  participantId,
  onStartGame,
  starting,
  startError,
  onLeave,
  chatMessages,
  onSendChat,
}: LobbyScreenProps) {
  const { room, error, toggleReady, kicked } = useRoomLobby(roomId, accessToken, participantId);
  // 토스트는 Figma `Shared / Toast`의 Type에 대응한다 (code/link 복사 = 체크, 방장 위임 = 왕관)
  const [toast, setToast] = useState<{ text: string; tone: 'check' | 'host' } | null>(null);
  const [courseCollapsed, setCourseCollapsed] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [readyPending, setReadyPending] = useState(false);
  // 강퇴 확인 팝업 대상. 닉네임은 팝업 문구용.
  const [kickTarget, setKickTarget] = useState<{ participantId: string; nickname: string } | null>(
    null,
  );
  const [kickPending, setKickPending] = useState(false);
  const [courseEditorOpen, setCourseEditorOpen] = useState(false);
  // 시작 버튼 → 사전 점검 모달 → (전부 통과) → 실제 시작. 인식이 외부 자원(MediaPipe CDN,
  // AI 서버)에 의존하는 게임이 코스에 있으면, 게임에 들어간 뒤 인식만 실패하는 것보다 여기서
  // 걸러내는 편이 싸다.
  const [preflightOpen, setPreflightOpen] = useState(false);

  // 코스는 서버가 원본이다 — 방장이 저장하면 member:game-updated로 전원 화면이 맞춰진다.
  const { course, games, error: courseError, saving: courseSaving, saveCourse } = useCourse(
    roomId,
    accessToken,
  );
  const topicsByGameId = useTopics(games, accessToken);

  // 내 캠/마이크 상태·토글 (내 타일의 정보 바에 버튼으로 노출)
  const { localParticipant, isCameraEnabled, isMicrophoneEnabled } = useLocalParticipant();

  // 카메라 트랙(없으면 placeholder 타일)을 참가자별로 하나씩
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });

  const self = room?.participants.find((p) => p.participantId === participantId);
  const isHost = participantId === room?.hostParticipantId;
  // 방장을 제외한 전원이 준비 완료인가 — 게임 시작 게이트 (혼자면 바로 시작 가능)
  const allOthersReady =
    !!room &&
    room.participants
      .filter((p) => p.participantId !== room.hostParticipantId)
      .every((p) => p.ready);
  // 이전 코스 결과 화면에 아직 남아 있는 사람. 방을 떠난 게 아니라 자리를 지키고 있을 뿐이라
  // 타일에 "게임 중"으로 표시되고, 전원이 돌아오기 전엔 다음 코스를 시작할 수 없다
  // (서버도 ROOM_NOT_ALL_RETURNED로 거부한다).
  // inLobby가 명시적으로 false일 때만 "게임 중"으로 본다 — 필드를 안 내려주는 구버전
  // 백엔드(undefined)에서 !p.inLobby로 판정하면 방금 만든 방의 전원이 "게임 중"으로 굳는다.
  const stillInResult = room?.participants.filter((p) => p.inLobby === false) ?? [];
  const allReturned = stillInResult.length === 0;
  // 타일 map 안에서 isHost가 "이 타일 주인이 방장인가"로 섀도잉되므로, "내가 방장인가"는 별칭으로 들고 간다.
  const amHost = isHost;
  // 타일 테두리·표시에 쓸 참가자 정보 (LiveKit identity == participantId)
  const infoByIdentity = new Map(
    (room?.participants ?? []).map((p, index) => [
      p.participantId,
      {
        nickname: p.nickname,
        ready: p.ready,
        isHost: p.participantId === room?.hostParticipantId,
        offline: p.connectionStatus === 'DISCONNECTED',
        inResult: p.inLobby === false,
        colorIndex: (index % 4) + 1,
      },
    ]),
  );
  // useTracks는 로컬 참가자를 항상 맨 앞에 놓기 때문에, 각자 자기 타일이 좌측 상단에 오는
  // 서로 다른 배치를 보게 된다("왼쪽에서 두 번째" 같은 말이 안 통함). 서버 스냅샷의 참가자
  // 순서(= 입장 순서. RedisParticipantRepository.findAll이 joined_at으로 정렬해서 내려준다)에
  // 맞춰 재정렬해 전원이 같은 자리 배치를 보게 한다. 타일 색(colorIndex)도 같은 순서를 쓰므로
  // 자리와 색이 함께 고정된다. 스냅샷에 아직 반영 안 된 트랙은 뒤로 보낸다.
  const joinOrderByIdentity = new Map(
    (room?.participants ?? []).map((p, index) => [p.participantId, index]),
  );
  const maxPlayers = room?.maxPlayers ?? 0;
  // 타일은 방 정원(maxPlayers)을 절대 넘지 않아야 한다. LiveKit 트랙(미디어 실제)과 서버
  // 스냅샷(room.participants)은 입·퇴장 순간 잠깐 어긋날 수 있는데, 스냅샷에 아직 없는
  // 트랙은 정렬에서 뒤로 밀리므로 정원만큼 잘라내면 유령/미반영 트랙이 제거된다.
  const orderedTracks = [...tracks]
    .sort(
      (a, b) =>
        (joinOrderByIdentity.get(a.participant.identity) ?? Number.MAX_SAFE_INTEGER) -
        (joinOrderByIdentity.get(b.participant.identity) ?? Number.MAX_SAFE_INTEGER),
    )
    .slice(0, maxPlayers || undefined);

  const joinedCount = room?.participants.length ?? 0;
  // 빈 슬롯은 스냅샷 인원이 아니라 "실제로 그리는 타일 수" 기준으로 채운다. 예전엔
  // (정원 - 스냅샷 인원)개를 그려서, 트랙과 스냅샷이 어긋나면 정원보다 많은 칸이 떴다
  // (스냅샷 1명 + 트랙 2개 → 2 + (4-1) = 5칸). 이제 항상 정확히 정원만큼만 나온다.
  const emptySlots = Math.max(0, maxPlayers - orderedTracks.length);
  // 그리드 배치(2인 한 줄 / 3인 아래 줄 가운데)를 고르는 데 쓴다 = 항상 정원과 같다
  const tileCount = orderedTracks.length + emptySlots;

  // Figma 게임 구성 카드 제목 옆의 요약 문구 ("3세트 · 총 11라운드")
  const courseItems = course?.items ?? [];
  const totalRounds = courseItems.reduce((sum, item) => sum + item.roundCount, 0);

  // 채팅 닉네임에 입힐 플레이어 대표색 (데이터 채널 payload에는 닉네임만 있어서 닉네임 기준 매핑).
  // 크림 배경 위 글자라서 원색이 아니라 --pap-player-N-text(읽히도록 보정한 값)를 쓴다.
  const colorByNickname = new Map(
    (room?.participants ?? []).map((p, index) => [
      p.nickname,
      `var(--pap-player-${(index % 4) + 1}-text)`,
    ]),
  );

  // 토스트 타이머는 ref로 들고 간다. effect의 cleanup에 걸면 room 스냅샷이 갱신될 때마다
  // (하트비트·준비 상태 변경 등) 타이머가 취소돼서 토스트가 안 사라진다.
  const toastTimerRef = useRef<number | null>(null);
  const showToast = useCallback((text: string, tone: 'check' | 'host' = 'check') => {
    if (toastTimerRef.current !== null) clearTimeout(toastTimerRef.current);
    setToast({ text, tone });
    toastTimerRef.current = window.setTimeout(
      () => {
        setToast(null);
        toastTimerRef.current = null;
      },
      tone === 'host' ? 2600 : 1500,
    );
  }, []);

  // 언마운트 시에만 정리
  useEffect(
    () => () => {
      if (toastTimerRef.current !== null) clearTimeout(toastTimerRef.current);
    },
    [],
  );

  const copy = async (kind: 'code' | 'link') => {
    if (!room) return;
    // 초대 링크 형식은 백엔드 RoomInviteLinkGenerator({frontend}/rooms/join?code=)와 동일하게 맞춘다.
    const text =
      kind === 'code' ? room.roomCode : `${window.location.origin}/rooms/join?code=${room.roomCode}`;
    try {
      await navigator.clipboard.writeText(text);
      // Figma `Shared / Toast` Type=Code Copied / Link Copied 문구
      showToast(kind === 'code' ? '참여 코드를 복사했어요' : '초대 링크를 복사했어요');
    } catch {
      // clipboard 접근이 막힌 환경(비 HTTPS 등) — 코드는 화면에 그대로 보이니 조용히 넘어간다.
    }
  };

  const handleKick = async () => {
    if (!kickTarget || kickPending) return;
    setKickPending(true);
    try {
      await roomApi.kickMember(roomId, kickTarget.participantId, accessToken);
      // 목록 갱신은 서버가 쏘는 member:left(KICKED) 브로드캐스트가 처리한다.
      setKickTarget(null);
    } catch (err) {
      setKickTarget(null);
      showToast(err instanceof RoomApiError ? err.message : '강퇴에 실패했어요');
    } finally {
      setKickPending(false);
    }
  };

  const handleToggleReady = async () => {
    if (!self || readyPending) return;
    // 카메라가 꺼진 채로 준비하면, 게임에 들어간 뒤엔 켤 방법이 없다 — 게임 화면(닌자/몸으로
    // 말해요/물건 가져오기)에는 카메라 토글이 없고 대기방에만 있다. 캠 없이 신체 인식 게임을
    // 하는 셈이 되므로 준비 자체를 막는다.
    if (!isCameraEnabled) {
      showToast(CAMERA_OFF_MESSAGE);
      return;
    }
    setReadyPending(true);
    try {
      await toggleReady(!self.ready);
    } catch {
      showToast('준비 상태 변경에 실패했어요');
    } finally {
      setReadyPending(false);
    }
  };

  // 방장 위임 알림 — 서버 host:changed로 room.hostParticipantId가 바뀌면 토스트를 띄운다.
  // (Figma `Shared / Toast` Type=Host Transferred) 첫 진입 시에는 띄우지 않는다.
  const prevHostRef = useRef<string | null>(null);
  useEffect(() => {
    const nextHost = room?.hostParticipantId ?? null;
    if (!nextHost) return;
    const prevHost = prevHostRef.current;
    prevHostRef.current = nextHost;
    // 첫 스냅샷(prevHost 없음)이나 변화 없음이면 알리지 않는다
    if (!prevHost || prevHost === nextHost) return;
    const nickname =
      room?.participants.find((p) => p.participantId === nextHost)?.nickname ?? '알 수 없음';
    showToast(`방장이 ${nickname}님으로 변경됐어요`, 'host');
  }, [room, showToast]);

  // 준비를 마친 뒤 카메라를 끄면 준비를 되돌린다 — 위에서 "카메라 꺼짐 → 준비 불가"만 막으면
  // 준비한 다음에 끄는 순서로 우회된다. 방장은 준비 토글을 쓰지 않고(시작 버튼이 곧 준비 의사)
  // 방 생성 시부터 ready=true인 불변식이 있어서 제외한다 — 방장 캠은 아래 시작 게이트가 본다.
  // (showToast는 useCallback으로 고정돼 있어 의존성에 넣어도 재실행 폭주가 없다)
  useEffect(() => {
    if (amHost || isCameraEnabled) return;
    if (!self?.ready) return;
    showToast(CAMERA_OFF_READY_CLEARED);
    void toggleReady(false).catch(() => {
      // 실패해도 서버 ready는 그대로다 — 시작 게이트가 여전히 막으므로 조용히 넘어간다.
    });
  }, [amHost, isCameraEnabled, self?.ready, toggleReady, showToast]);

  // 스페이스바 단축키 — 방장은 게임 시작, 참가자는 준비 토글. 마우스 없이 대기방을 진행할 수
  // 있게 한다. 오작동 방지 가드:
  //  - 채팅 입력 등 폼 요소/버튼에 포커스가 있으면 무시 (입력 중 스페이스, 포커스된 버튼의
  //    네이티브 스페이스 클릭과의 이중 동작 방지)
  //  - 팝업(코스 편집/나가기/강퇴 확인)이 열려 있으면 무시
  //  - 꾹 누르고 있을 때의 반복 입력(e.repeat) 무시 — 준비 상태가 깜빡거리지 않게
  // 시작 조건 미달이면 시작 대신 그 이유를 토스트로 보여준다(hover 말풍선과 같은 문구).
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || e.repeat) return;
      const target = e.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.tagName === 'BUTTON' ||
          target.isContentEditable)
      ) {
        return;
      }
      if (courseEditorOpen || confirmLeave || kickTarget || preflightOpen) return;
      e.preventDefault(); // 페이지 스크롤 방지

      if (amHost) {
        if (starting) return;
        if (!course || course.items.length === 0) {
          showToast('코스를 정해주세요!');
          return;
        }
        if (!isCameraEnabled) {
          showToast(CAMERA_OFF_HOST_MESSAGE);
          return;
        }
        if (!allReturned) {
          showToast('아직 결과 화면을 보고 있는 참가자가 있어요!');
          return;
        }
        if (!allOthersReady) {
          showToast('모든 참가자가 준비를 완료해야 해요!');
          return;
        }
        setPreflightOpen(true);
      } else {
        void handleToggleReady();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  return (
    // .camon-stage = 뷰포트를 덮는 전체 화면 껍데기 (스크롤 없음).
    // 글자·여백·사이드 폭은 고정, 남는 공간은 비디오 타일과 채팅이 흡수한다.
    <div className="lobby-screen camon-stage">
      <div className="lobby-screen__bg-bottom" />

      {/* 확정안: 방 안에서 로고 클릭 = 바로 이동이 아니라 나가기 확인 팝업 */}
      <img
        className="lobby-screen__logo"
        src="/assets/cam-on-logo-v3.png"
        alt="CAM, ON!"
        onClick={() => setConfirmLeave(true)}
      />

      <div className="lobby-screen__body">
        <section className="lobby-screen__stage">
          {error && <p className="lobby-screen__error">방 정보를 불러오지 못했습니다: {error}</p>}
          <div
            className={`lobby-screen__grid${
              tileCount === 3 ? ' lobby-screen__grid--3' : ''
            }${tileCount <= 2 ? ' lobby-screen__grid--2' : ''}`}
          >
            {orderedTracks.map((trackRef) => {
              const identity = trackRef.participant.identity;
              const info = infoByIdentity.get(identity);
              const isHost = info?.isHost ?? false;
              const ready = info?.ready ?? false;
              // 아직 코스 결과 화면에 있는 사람 — 자리·순서·방장 자격은 그대로 두고 "게임 중"만
              // 덮는다. 준비 배지는 의미가 없으니 이 사람에겐 띄우지 않는다.
              const inResult = info?.inResult ?? false;
              // 방장은 게임 시작 게이트를 위해 내부적으로 ready=true지만, 대기방 UI엔 준비 배지·
              // 상태를 표시하지 않는다 — 방장은 준비 대상이 아니라 게임을 시작하는 주체이기 때문.
              const showReady = ready && !isHost && !inResult;
              const isMe = identity === participantId;
              // 연결이 끊긴 참가자는 재접속 유예(15초) 동안 자리를 지킨 채 회색으로만 표시된다.
              // 유예가 끝나면 서버가 member:left를 보내고 그때 타일이 사라진다(방장이면 위임까지).
              const offline = info?.offline ?? false;
              const nickname = info?.nickname ?? trackRef.participant.name ?? '...';
              return (
                <div
                  key={identity}
                  className={`lobby-tile${info ? ` lobby-tile--p${info.colorIndex}` : ''}${
                    showReady ? ' lobby-tile--ready' : ''
                  }${offline ? ' lobby-tile--offline' : ''}${
                    inResult ? ' lobby-tile--in-result' : ''
                  }`}
                >
                  {/* Figma의 캠 대기 화면 — 크림 원 + 대표색 원 + 이니셜.
                      비디오 트랙이 붙으면 위에 얹히는 <video>가 그대로 덮는다. */}
                  <span className="lobby-tile__avatar" aria-hidden="true">
                    <span className="lobby-tile__avatar-ring" />
                    <span className="lobby-tile__avatar-initial">{[...nickname][0] ?? '?'}</span>
                  </span>
                  {/* 아직 결과 화면에 있는 사람은 캠을 붙이지 않는다 — LiveKit 트랙은 방을 떠나기
                      전까진 계속 살아 있어서, 그냥 두면 결과 화면에 있는 사람이 대기방에도
                      똑같이 비친다. 자리·순서·이름은 남기고 화면만 아바타로 대신한다
                      (결과 화면 쪽도 대칭으로 대기방에 간 사람의 캠을 내린다). */}
                  {!inResult && <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />}
                  {/* 방장 표시 — 영상 우측 상단 왕관. 내 화면이든 게스트 화면이든 동일. */}
                  {isHost && (
                    <span className="lobby-tile__host-badge" title="방장" aria-label="방장">
                      {CrownIcon}
                    </span>
                  )}
                  {/* 준비 완료 — 영상 좌측 상단 스티커 배지 (하단 바 아이콘보다 눈에 잘 띄게) */}
                  {showReady && (
                    <span className="lobby-tile__ready-badge">
                      {CheckIcon}
                      READY!
                    </span>
                  )}
                  {/* Figma `Shared / User Card / Reconnecting Overlay` — 하트비트 재연결 유예(15초) */}
                  {offline && (
                    <div className="lobby-tile__offline">
                      {SpinnerIcon}
                      <span className="lobby-tile__offline-title">
                        연결을 다시 확인하고 있어요
                      </span>
                      <span className="lobby-tile__offline-sub">
                        15초 동안 응답이 없으면 방에서 나가요
                      </span>
                    </div>
                  )}
                  {/* 결과 화면에 남아 있는 사람. 연결 끊김이 더 급한 상태라 그때는 양보한다.
                      (READY! 배지는 위쪽에서 이미 그린다 — showReady가 inResult를 제외한다) */}
                  {inResult && !offline && (
                    <div className="lobby-tile__in-result">
                      {InResultIcon}
                      <span>게임 중</span>
                    </div>
                  )}
                  <div className="lobby-tile__bar">
                    <span className="lobby-tile__name">{nickname}</span>
                    {/* 타일이 입장 순서로 고정돼 "좌측 상단 = 나"가 아니므로 역할 옆에 표시한다 */}
                    <span className="lobby-tile__role">
                      {info?.isHost ? '방장' : '참여자'}
                      {isMe ? ' · 나' : ''}
                    </span>
                    {/* 내 캠/마이크 토글은 항상 노출 (28×28, 채팅 전송 버튼과 같은 규격) */}
                    {isMe && (
                      <span className="lobby-tile__controls">
                        <button
                          type="button"
                          className={`lobby-tile__control${isCameraEnabled ? '' : ' lobby-tile__control--off'}`}
                          data-button-sound={isCameraEnabled ? 'cancel' : 'basic'}
                          onClick={() => void localParticipant.setCameraEnabled(!isCameraEnabled)}
                          title={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                          aria-label={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                        >
                          {isCameraEnabled ? CamOnIcon : CamOffIcon}
                        </button>
                        <button
                          type="button"
                          className={`lobby-tile__control${isMicrophoneEnabled ? '' : ' lobby-tile__control--off'}`}
                          data-button-sound={isMicrophoneEnabled ? 'cancel' : 'basic'}
                          onClick={() =>
                            void localParticipant.setMicrophoneEnabled(!isMicrophoneEnabled)
                          }
                          title={isMicrophoneEnabled ? '마이크 끄기' : '마이크 켜기'}
                          aria-label={isMicrophoneEnabled ? '마이크 끄기' : '마이크 켜기'}
                        >
                          {isMicrophoneEnabled ? MicOnIcon : MicOffIcon}
                        </button>
                      </span>
                    )}
                    {/* 방장에게만: 다른 참가자 타일에 강퇴 버튼. 대상이 방장 타일인 경우는
                        없다(방장=나, 내 타일엔 안 그림). 실제 실행은 확인 팝업을 거친다. */}
                    {amHost && !isMe && info && (
                      <span className="lobby-tile__controls lobby-tile__controls--kick">
                        <button
                          type="button"
                          className="lobby-tile__control lobby-tile__control--kick"
                          onClick={() =>
                            setKickTarget({ participantId: identity, nickname: info.nickname })
                          }
                          title="강퇴"
                          aria-label={`${info.nickname} 강퇴`}
                        >
                          {KickIcon}
                        </button>
                      </span>
                    )}
                    {/* 준비 상태는 영상 좌측 상단 READY! 배지가 전담한다 — 하단 바에는 표시하지 않는다 */}
                  </div>
                </div>
              );
            })}
            {Array.from({ length: emptySlots }, (_, i) => (
              <div key={`empty-${i}`} className="lobby-tile lobby-tile--empty">
                {/* 번호는 실제로 그려진 타일 수 기준 (스냅샷 인원과 어긋나도 어긋나지 않게).
                    폰트·색은 .lobby-tile__empty-slot이 Figma 값으로 정하므로
                    pap-pixel-title(잉크색 강제)은 붙이지 않는다. */}
                <span className="lobby-tile__empty-slot">P{orderedTracks.length + i + 1}</span>
                <span className="lobby-tile__empty-hint">친구 입장 대기 중...</span>
              </div>
            ))}
          </div>
        </section>

        {/* 사이드 열 — 폭 416 고정. 티켓·게임구성·하단버튼은 높이 고정, 채팅만 유동. */}
        <aside
          className={`lobby-screen__side${
            courseCollapsed ? ' lobby-screen__side--chat-expanded' : ''
          }`}
        >
          {room && (
            <div className="lobby-screen__ticket">
              <div className="lobby-screen__code-area">
                <strong className="lobby-screen__code-title">참여 코드</strong>
                {/* 방 코드를 아케이드 티켓 타일로 — 홀수는 크림, 짝수는 밝은 하늘색 */}
                <span
                  className="lobby-screen__code-tiles"
                  aria-label={`참여 코드 ${room.roomCode}`}
                >
                  {room.roomCode.split('').map((ch, i) => (
                    <span key={i} className="lobby-screen__code-tile">
                      {ch}
                    </span>
                  ))}
                </span>
                <span className="lobby-screen__ticket-actions">
                  <button
                    type="button"
                    className="lobby-screen__icon-btn"
                    onClick={() => copy('code')}
                    title="코드 복사"
                    aria-label="코드 복사"
                  >
                    {CopyIcon}
                  </button>
                  <button
                    type="button"
                    className="lobby-screen__icon-btn"
                    onClick={() => copy('link')}
                    title="초대 링크 복사"
                    aria-label="초대 링크 복사"
                  >
                    {LinkIcon}
                  </button>
                </span>
              </div>
            </div>
          )}

          {/* 게임 구성 카드 — 채팅을 위로 펼치면 이 카드 자리를 채팅이 가져간다 */}
          {!courseCollapsed && (
            <div className="lobby-screen__course">
            <h2 className="lobby-screen__panel-title">게임 구성</h2>
            {courseItems.length > 0 && (
              <span className="lobby-screen__panel-sub">
                {courseItems.length}세트 · 총 {totalRounds}라운드
              </span>
            )}
            {/* 코스 편집은 방장만. 참가자에게는 버튼 자체를 띄우지 않는다(읽기 전용) */}
            {amHost && (
              <button
                type="button"
                className="lobby-screen__course-edit"
                onClick={() => setCourseEditorOpen(true)}
                title="게임 순서와 라운드 수 정하기"
              >
                구성 변경
              </button>
            )}
            {/* Figma는 3세트 기준(104×91, 간격 36). 4세트 이상이면 카드를 줄이지 않고
                좌우 스크롤로 넘긴다 (스크롤바는 마우스를 올릴 때만 보인다). */}
            <ol className="lobby-screen__sets">
              {courseItems.map((item) => (
                <li
                  key={item.idx}
                  className="lobby-screen__set"
                  title={`${String(item.idx).padStart(2, '0')} ${
                    GAME_LABELS[item.gameName] ?? item.gameName
                  } ${item.roundCount}라운드${item.topicName ? ` · ${item.topicName}` : ''}`}
                >
                  <span className="lobby-screen__set-number">
                    {String(item.idx).padStart(2, '0')}
                  </span>
                  <strong className="lobby-screen__set-name">
                    {GAME_LABELS[item.gameName] ?? item.gameName}
                  </strong>
                  <small className="lobby-screen__set-rounds">{item.roundCount}라운드</small>
                </li>
              ))}
            </ol>
            {courseItems.length === 0 && (
              <p className="lobby-screen__course-empty">
                {amHost
                  ? '구성 변경을 눌러 게임을 담아 주세요.'
                  : '방장이 게임을 정하는 중이에요.'}
              </p>
            )}
              {courseError && <p className="lobby-screen__course-empty">{courseError}</p>}
            </div>
          )}

          <div className="lobby-screen__chat">
            <h2 className="lobby-screen__panel-title">채팅</h2>
            {/* 확정안: 위쪽 꺾쇠 = 채팅을 게임 구성 자리까지 확장 / 아래쪽 꺾쇠 = 다시 접기 */}
            <button
              type="button"
              className="lobby-screen__icon-btn lobby-screen__chat-toggle"
              onClick={() => setCourseCollapsed((v) => !v)}
              title={courseCollapsed ? '채팅 접기' : '채팅 넓게 보기'}
              aria-label={courseCollapsed ? '채팅 접기' : '채팅 넓게 보기'}
            >
              {courseCollapsed ? ChevronDownIcon : ChevronUpIcon}
            </button>
            <ChatPanel
              variant="docked"
              messages={chatMessages}
              onSend={onSendChat}
              nicknameColorFor={(nickname) => colorByNickname.get(nickname)}
            />
          </div>

          <div className="lobby-screen__actions">
          <button
            type="button"
            className="pap-pixel-btn lobby-screen__settings-btn"
            onClick={() => setSettingsOpen(true)}
            title="환경설정 — 카메라/마이크/인식 테스트"
          >
            {GearIcon}
            환경설정
          </button>
          {/* 주 액션은 하나로 통일 — 방장: 게임 시작 / 참가자: 준비 토글 */}
          {isHost ? (
            // 공통 요구사항: 전원 준비 완료여야 시작 가능 (방장 본인 제외 — 방장은 시작이 곧 준비).
            // 왜 안 눌리는지는 네이티브 title이 아니라 CSS 말풍선으로 보여준다 — 비활성 버튼의
            // title 툴팁은 뜨기까지 1초쯤 걸리고 눈에 잘 안 띄어서 "버튼이 고장났다"로 읽힌다.
            <span
              className="lobby-screen__start-wrap"
              data-hint={
                course && course.items.length === 0
                  ? '코스를 정해주세요!'
                  : !isCameraEnabled
                    ? CAMERA_OFF_HOST_MESSAGE
                    : !allReturned
                      ? '아직 결과 화면을 보고 있는 참가자가 있어요!'
                      : allOthersReady
                        ? undefined
                        : '모든 참가자가 준비를 완료해야 해요!'
              }
            >
              <button
                type="button"
                className="pap-pixel-btn lobby-screen__primary-btn"
                // 코스가 비면 시작할 게 없고(서버도 COURSE_EMPTY로 거부), 전원 준비 전에도
                // 서버가 거부하므로(ROOM_NOT_ALL_READY) 둘 다 미리 막는다. 이전 코스 결과
                // 화면에 남아 있는 사람이 있을 때도 마찬가지(ROOM_NOT_ALL_RETURNED).
                // 방장 카메라는 서버가 알 수 없어서(로컬 상태) 여기서만 막는다 — 게임에
                // 들어가면 켤 방법이 없다.
                disabled={
                  starting ||
                  !room ||
                  !course ||
                  course.items.length === 0 ||
                  !isCameraEnabled ||
                  !allReturned ||
                  !allOthersReady
                }
                // 바로 시작하지 않고 사전 점검 모달을 거친다 (MediaPipe/AI 서버 체크)
                onClick={() => setPreflightOpen(true)}
              >
                {PlayIcon}
                {starting
                  ? '시작 중...'
                  : !isCameraEnabled
                    ? '카메라 꺼짐'
                    : !allReturned
                      ? '복귀 대기 중...'
                      : allOthersReady
                        ? '게임 시작'
                        : '준비 대기 중...'}
              </button>
            </span>
          ) : (
            // Figma `Lobby / Start Action`(미준비, 오렌지) ↔ `Lobby / Ready Complete Action`
            // (준비 완료, 살구 #ffc56e). 둘 다 Material check 아이콘 + 13px 라벨, 그림자 5px 유지.
            // 카메라가 꺼져 있으면 준비할 수 없다 — 이유는 말풍선으로.
            <span
              className="lobby-screen__start-wrap"
              data-hint={isCameraEnabled ? undefined : CAMERA_OFF_HINT}
            >
              <button
                type="button"
                className={`pap-pixel-btn ${
                  self?.ready
                    ? 'lobby-screen__ready-btn--on'
                    : 'lobby-screen__primary-btn lobby-screen__primary-btn--ready'
                }`}
                data-button-sound={self?.ready ? 'cancel' : 'ready'}
                disabled={readyPending || !self || !isCameraEnabled}
                onClick={() => void handleToggleReady()}
              >
                {CheckIcon}
                {readyPending
                  ? '...'
                  : !isCameraEnabled
                    ? '카메라 꺼짐'
                    : self?.ready
                      ? '준비 완료'
                      : '준비 하기'}
              </button>
            </span>
          )}
          </div>
          {startError && <p className="lobby-screen__error">{startError}</p>}
        </aside>
      </div>

      {/* Figma `Shared / Toast` — 화면 상단 중앙, 아이콘 + 문구. 자동으로 사라진다. */}
      {toast && (
        <div className={`pap-toast${toast.tone === 'host' ? ' pap-toast--host' : ''}`}>
          {toast.tone === 'host' ? CrownIcon : CheckCircleIcon}
          {toast.text}
        </div>
      )}
      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} />}
      {/* 게임 구성 — 팝업이 아니라 대기방 위를 덮는 전체 화면 (라우트 이동 없이 연결 유지) */}
      {courseEditorOpen && (
        <GameSetupScreen
          games={games}
          course={course}
          topicsByGameId={topicsByGameId}
          playerCount={joinedCount}
          saving={courseSaving}
          saveError={courseError}
          onSave={saveCourse}
          onClose={() => setCourseEditorOpen(false)}
        />
      )}
      {/* 시작 버튼을 누르면 바로 시작하지 않고 사전 점검을 거친다 — 전부 통과하면 모달이
          스스로 onProceed를 불러 시작한다. 실패하면 시작하지 않고 이유를 보여준다. */}
      {preflightOpen && (
        <StartPreflightModal
          course={course}
          gameNameOf={(gameId) => games.find((game) => game.gameId === gameId)?.name ?? null}
          onProceed={onStartGame}
          onCancel={() => setPreflightOpen(false)}
          starting={starting}
          startError={startError}
        />
      )}
      {confirmLeave && (
        <PixelConfirmModal
          title="정말 방을 나갈까요?"
          message="현재 방과 게임 결과에서 나가 메인 화면으로 이동해요."
          confirmLabel="방 나가기"
          cancelLabel="취소"
          tone="danger"
          onConfirm={onLeave}
          onCancel={() => setConfirmLeave(false)}
        />
      )}
      {kickTarget && (
        <PixelConfirmModal
          title={`'${kickTarget.nickname}' 님을 강퇴할까요?`}
          message="강퇴된 참가자는 이 방에 다시 들어올 수 없어요."
          confirmLabel={kickPending ? '강퇴 중...' : '강퇴'}
          cancelLabel="취소"
          tone="danger"
          onConfirm={() => void handleKick()}
          onCancel={() => !kickPending && setKickTarget(null)}
        />
      )}
      {/* 내가 강퇴당한 경우 — member:left(KICKED)에서 내 id를 확인한 결과. 확인을 눌러야
          방을 떠난다(onLeave가 정리 + 메인 이동. leaveRoom API는 404가 나지만 조용히 무시됨). */}
      {kicked && (
        <PixelConfirmModal
          title="강퇴되었습니다"
          message="방장이 회원님을 방에서 내보냈어요."
          confirmLabel="메인으로"
          onConfirm={onLeave}
        />
      )}
    </div>
  );
}
