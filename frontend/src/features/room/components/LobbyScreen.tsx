import { useEffect, useState } from 'react';
import { ParticipantTile, useLocalParticipant, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { ChatPanel } from '../../chat/components/ChatPanel';
import type { ChatMessage } from '../../chat/hooks/useRoomChat';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { roomApi, RoomApiError } from '../api/roomApi';
import { useRoomLobby } from '../hooks/useRoomLobby';
import { CourseEditorModal } from '../../course/components/CourseEditorModal';
import { GAME_LABELS, type GameName } from '../../course/api/courseApi';
import { useCourse } from '../../course/hooks/useCourse';
import { useTopics } from '../../course/hooks/useTopics';
import {
  CamOffIcon,
  CamOnIcon,
  CharadesGameIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CopyIcon,
  DisconnectedIcon,
  FetchGameIcon,
  GearIcon,
  HandGameIcon,
  KickIcon,
  LinkIcon,
  MicOffIcon,
  MicOnIcon,
  ReadyIcon,
  WaitingIcon,
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

// 게임 이름(games.name) -> 칩에 쓰는 아이콘. 최대 7세트까지 가므로 이름 대신 아이콘 +
// 라운드 수만 있는 컴팩트 칩으로 표시하고, 전체 이름은 툴팁으로 보여준다.
const GAME_ICONS: Record<GameName, typeof FetchGameIcon> = {
  FETCH_OBJECT: FetchGameIcon,
  NINJA: HandGameIcon,
  CHARADES: CharadesGameIcon,
};

// 대기방 전체 화면 — 피그마 로비 시안 구조를 Pixel Arcade Plaza 테마로 구현.
// 참가자 정보(이름/역할/준비, 내 캠·마이크 토글)는 비디오 타일 자체에 표시하고,
// 사이드바는 게임 구성 + 채팅 + 액션. 참가자는 항상 닉네임으로만 표시한다.
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
  const [toast, setToast] = useState<string | null>(null);
  const [courseCollapsed, setCourseCollapsed] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [readyPending, setReadyPending] = useState(false);
  // 강퇴 확인 팝업 대상. 닉네임은 팝업 문구용.
  const [kickTarget, setKickTarget] = useState<{ participantId: string; nickname: string } | null>(
    null,
  );
  const [kickPending, setKickPending] = useState(false);
  const [courseEditorOpen, setCourseEditorOpen] = useState(false);

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
  const orderedTracks = [...tracks].sort(
    (a, b) =>
      (joinOrderByIdentity.get(a.participant.identity) ?? Number.MAX_SAFE_INTEGER) -
      (joinOrderByIdentity.get(b.participant.identity) ?? Number.MAX_SAFE_INTEGER),
  );

  const joinedCount = room?.participants.length ?? 0;
  const emptySlots = Math.max(0, (room?.maxPlayers ?? 0) - joinedCount);

  // 채팅 닉네임에 입힐 플레이어 대표색 (데이터 채널 payload에는 닉네임만 있어서 닉네임 기준 매핑)
  const colorByNickname = new Map(
    (room?.participants ?? []).map((p, index) => [p.nickname, `var(--pap-player-${(index % 4) + 1})`]),
  );

  const showToast = (text: string) => {
    setToast(text);
    setTimeout(() => setToast(null), 1500);
  };

  const copy = async (kind: 'code' | 'link') => {
    if (!room) return;
    // 초대 링크 형식은 백엔드 RoomInviteLinkGenerator({frontend}/rooms/join?code=)와 동일하게 맞춘다.
    const text =
      kind === 'code' ? room.roomCode : `${window.location.origin}/rooms/join?code=${room.roomCode}`;
    try {
      await navigator.clipboard.writeText(text);
      showToast(kind === 'code' ? '코드 복사 완료!' : '링크 복사 완료!');
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
    setReadyPending(true);
    try {
      await toggleReady(!self.ready);
    } catch {
      showToast('준비 상태 변경에 실패했어요');
    } finally {
      setReadyPending(false);
    }
  };

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
      if (courseEditorOpen || confirmLeave || kickTarget) return;
      e.preventDefault(); // 페이지 스크롤 방지

      if (amHost) {
        if (starting) return;
        if (!course || course.items.length === 0) {
          showToast('코스를 정해주세요!');
          return;
        }
        if (!allOthersReady) {
          showToast('모든 참가자가 준비를 완료해야 해요!');
          return;
        }
        onStartGame();
      } else {
        void handleToggleReady();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  return (
    <div className="lobby-screen">
      <header className="lobby-screen__header">
        {/* 확정안: 방 안에서 로고 클릭 = 바로 이동이 아니라 나가기 확인 팝업 */}
        <img
          className="lobby-screen__logo pap-pixel-img"
          src="/assets/cam-on-logo.png"
          alt="CAM, ON!"
          onClick={() => setConfirmLeave(true)}
        />
        <BackgroundMusic
          source="/assets/sounds/cozy-cartridge-club.mp3"
          className="lobby-screen__music-toggle"
        />
        {room && (
          <div className="lobby-screen__code-area">
            {/* 방 코드를 글자 타일(아케이드 티켓 느낌)로 — 좌측 픽셀 로고와 톤 맞춤 */}
            <span className="lobby-screen__code-tiles" aria-label={`참여 코드 ${room.roomCode}`}>
              {room.roomCode.split('').map((ch, i) => (
                <span key={i} className="lobby-screen__code-tile pap-pixel-title">
                  {ch}
                </span>
              ))}
            </span>
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
          </div>
        )}
      </header>

      <div className="lobby-screen__body">
        <section className="lobby-screen__stage">
          {error && <p className="lobby-screen__error">방 정보를 불러오지 못했습니다: {error}</p>}
          <div
            className={`lobby-screen__grid${
              orderedTracks.length + emptySlots === 3 ? ' lobby-screen__grid--3' : ''
            }`}
          >
            {orderedTracks.map((trackRef) => {
              const identity = trackRef.participant.identity;
              const info = infoByIdentity.get(identity);
              const isHost = info?.isHost ?? false;
              const ready = info?.ready ?? false;
              // 방장은 게임 시작 게이트를 위해 내부적으로 ready=true지만, 대기방 UI엔 준비 배지·
              // 상태를 표시하지 않는다 — 방장은 준비 대상이 아니라 게임을 시작하는 주체이기 때문.
              const showReady = ready && !isHost;
              const isMe = identity === participantId;
              // 연결이 끊긴 참가자는 재접속 유예(15초) 동안 자리를 지킨 채 회색으로만 표시된다.
              // 유예가 끝나면 서버가 member:left를 보내고 그때 타일이 사라진다(방장이면 위임까지).
              const offline = info?.offline ?? false;
              return (
                <div
                  key={identity}
                  className={`lobby-tile${info ? ` lobby-tile--p${info.colorIndex}` : ''}${
                    showReady ? ' lobby-tile--ready' : ''
                  }${offline ? ' lobby-tile--offline' : ''}`}
                >
                  <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
                  {/* 타일이 입장 순서로 고정돼 더는 "좌측 상단 = 나"가 아니므로 내 타일을 표시해준다.
                      READY 배지(우측 상단)와 반대쪽에 둬서 둘 다 떠도 겹치지 않는다. */}
                  {isMe && <span className="lobby-tile__me-badge">ME</span>}
                  {offline && (
                    <div className="lobby-tile__offline">
                      {DisconnectedIcon}
                      <span>연결 끊김</span>
                    </div>
                  )}
                  {showReady && <span className="lobby-tile__ready-badge">READY!</span>}
                  <div className="lobby-tile__bar">
                    <span className="lobby-tile__name">
                      {info?.nickname ?? trackRef.participant.name ?? '...'}
                    </span>
                    <span className="lobby-tile__role">{info?.isHost ? '방장' : '참여자'}</span>
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
                      <span className="lobby-tile__controls">
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
                    {!isHost && (
                      <span
                        className={`lobby-tile__status${ready ? ' lobby-tile__status--ready' : ''}`}
                        title={ready ? '준비 완료' : '대기 중'}
                      >
                        {ready ? ReadyIcon : WaitingIcon}
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
            {Array.from({ length: emptySlots }, (_, i) => (
              <div key={`empty-${i}`} className="lobby-tile lobby-tile--empty">
                <span className="lobby-tile__empty-slot pap-pixel-title">
                  P{joinedCount + i + 1}
                </span>
                <span className="lobby-tile__empty-hint">친구 소환 대기 중...</span>
              </div>
            ))}
          </div>
        </section>

        <aside className="lobby-screen__sidebar">
          {/* 세트가 많아 답답하면 접어서 채팅에 높이를 내줄 수 있다 */}
          <div
            className={`lobby-screen__course pap-pixel-card${
              courseCollapsed ? ' lobby-screen__course--collapsed' : ''
            }`}
          >
            <div className="lobby-screen__panel-head">
              <h2 className="lobby-screen__panel-title pap-pixel-title">게임 구성</h2>
              <span className="lobby-screen__panel-actions">
                {/* 코스 편집은 방장만. 참가자에게는 버튼 자체를 띄우지 않는다(읽기 전용) */}
                {amHost && (
                  <button
                    type="button"
                    className="pap-pixel-btn lobby-btn-sm"
                    onClick={() => setCourseEditorOpen(true)}
                    title="게임 순서와 라운드 수 정하기"
                  >
                    구성 변경
                  </button>
                )}
                <button
                  type="button"
                  className="lobby-screen__icon-btn lobby-screen__icon-btn--light"
                  onClick={() => setCourseCollapsed((v) => !v)}
                  title={courseCollapsed ? '펼치기' : '접기'}
                  aria-label={courseCollapsed ? '게임 구성 펼치기' : '게임 구성 접기'}
                >
                  {courseCollapsed ? ChevronDownIcon : ChevronUpIcon}
                </button>
              </span>
            </div>
            {/* 최대 7세트 — 칩 하나 = 게임 1세트. 아이콘만 보여주고 이름/주제는 툴팁으로 */}
            <ol className="lobby-screen__sets">
              {(course?.items ?? []).map((item) => (
                <li
                  key={item.idx}
                  className="lobby-screen__set"
                  title={`${String(item.idx).padStart(2, '0')} ${
                    GAME_LABELS[item.gameName] ?? item.gameName
                  } 1세트${item.topicName ? ` · ${item.topicName}` : ''}`}
                >
                  <span className="lobby-screen__set-icon">{GAME_ICONS[item.gameName]}</span>
                </li>
              ))}
            </ol>
            {course?.items.length === 0 && (
              <p className="lobby-screen__course-empty">
                {amHost
                  ? '구성 변경을 눌러 게임을 담아 주세요.'
                  : '방장이 게임을 정하는 중이에요.'}
              </p>
            )}
            {courseError && <p className="lobby-screen__course-empty">{courseError}</p>}
          </div>

          <div className="lobby-screen__chat pap-pixel-card">
            <div className="lobby-screen__panel-head">
              <h2 className="lobby-screen__panel-title pap-pixel-title">채팅</h2>
            </div>
            <ChatPanel
              variant="docked"
              messages={chatMessages}
              onSend={onSendChat}
              nicknameColorFor={(nickname) => colorByNickname.get(nickname)}
            />
          </div>

          <div className="lobby-screen__actions">
            {/* 환경설정은 톱니 아이콘만 (준비 중), 주 액션이 나머지 너비를 다 가진다 */}
            <button
              type="button"
              className="pap-pixel-btn lobby-screen__settings-btn"
              disabled
              title="환경설정 — 카메라/마이크/인식 테스트 (준비 중)"
              aria-label="환경설정"
            >
              {GearIcon}
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
                    : allOthersReady
                      ? undefined
                      : '모든 참가자가 준비를 완료해야 해요!'
                }
              >
                <button
                  type="button"
                  className="pap-pixel-btn pap-pixel-btn--coral"
                  // 코스가 비면 시작할 게 없고(서버도 COURSE_EMPTY로 거부), 전원 준비 전에도
                  // 서버가 거부하므로(ROOM_NOT_ALL_READY) 둘 다 미리 막는다.
                  disabled={
                    starting || !room || !course || course.items.length === 0 || !allOthersReady
                  }
                  onClick={() => onStartGame()}
                >
                  {starting ? '시작 중...' : allOthersReady ? '게임 시작 (Space)' : '준비 대기 중...'}
                </button>
              </span>
            ) : (
              // 준비되면 눌린 채 고정된 라임 버튼으로 — 누르는 순간의 "철컥" UX.
              // (확정안은 준비 버튼 제거 예정 — 백엔드 ready 규칙 정리 전까지 임시)
              <button
                type="button"
                className={`pap-pixel-btn${
                  self?.ready ? ' lobby-screen__ready-btn--on' : ' pap-pixel-btn--teal'
                }`}
                data-button-sound={self?.ready ? 'cancel' : 'ready'}
                disabled={readyPending || !self}
                onClick={() => void handleToggleReady()}
              >
                {readyPending ? '...' : self?.ready ? '준비 완료!' : '준비 하기 (Space)'}
              </button>
            )}
          </div>
          {startError && <p className="lobby-screen__error">{startError}</p>}
        </aside>
      </div>

      {toast && <div className="pap-toast">{toast}</div>}
      {courseEditorOpen && (
        <CourseEditorModal
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
      {confirmLeave && (
        <PixelConfirmModal
          title="정말 방을 나갈까요?"
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
