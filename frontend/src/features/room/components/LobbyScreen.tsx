import { useState } from 'react';
import { ParticipantTile, useLocalParticipant, useTracks } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { ChatPanel } from '../../chat/components/ChatPanel';
import type { ChatMessage } from '../../chat/hooks/useRoomChat';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { useRoomLobby } from '../hooks/useRoomLobby';
import {
  CamOffIcon,
  CamOnIcon,
  CharadesGameIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CopyIcon,
  FetchGameIcon,
  GearIcon,
  HandGameIcon,
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
  onStartGame: (participantTokens: string[]) => void;
  starting: boolean;
  startError: string | null;
  /** 사용자가 스스로 방을 나갈 때 (연결 정리 + 메인 이동은 상위가 처리) */
  onLeave: () => void;
  chatMessages: ChatMessage[];
  onSendChat: (text: string) => void;
}

// 코스(게임 구성) 백엔드 도메인이 아직 없어서 표시용 mock — course API가 생기면 교체.
// 최대 7세트까지 갈 수 있어서 아이콘 + 라운드 수만 있는 컴팩트 칩으로 표시한다.
const MOCK_COURSE = [
  { game: 'fetch', name: '물건 가져오기', rounds: 3, icon: FetchGameIcon },
  { game: 'ninja', name: '손동작 따라하기', rounds: 3, icon: HandGameIcon },
  { game: 'charades', name: '몸으로 말해요', rounds: 5, icon: CharadesGameIcon },
];

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
  const { room, error, toggleReady } = useRoomLobby(roomId, accessToken);
  const [toast, setToast] = useState<string | null>(null);
  const [courseCollapsed, setCourseCollapsed] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [readyPending, setReadyPending] = useState(false);

  // 내 캠/마이크 상태·토글 (내 타일의 정보 바에 버튼으로 노출)
  const { localParticipant, isCameraEnabled, isMicrophoneEnabled } = useLocalParticipant();

  // 카메라 트랙(없으면 placeholder 타일)을 참가자별로 하나씩
  const tracks = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  });

  const self = room?.participants.find((p) => p.participantId === participantId);
  const isHost = participantId === room?.hostParticipantId;
  // 타일 테두리·표시에 쓸 참가자 정보 (LiveKit identity == participantId)
  const infoByIdentity = new Map(
    (room?.participants ?? []).map((p, index) => [
      p.participantId,
      {
        nickname: p.nickname,
        ready: p.ready,
        isHost: p.participantId === room?.hostParticipantId,
        colorIndex: (index % 4) + 1,
      },
    ]),
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
              tracks.length + emptySlots === 3 ? ' lobby-screen__grid--3' : ''
            }`}
          >
            {tracks.map((trackRef) => {
              const identity = trackRef.participant.identity;
              const info = infoByIdentity.get(identity);
              const ready = info?.ready ?? false;
              const isMe = identity === participantId;
              return (
                <div
                  key={identity}
                  className={`lobby-tile${info ? ` lobby-tile--p${info.colorIndex}` : ''}${
                    ready ? ' lobby-tile--ready' : ''
                  }`}
                >
                  <ParticipantTile trackRef={trackRef} disableSpeakingIndicator />
                  {ready && <span className="lobby-tile__ready-badge">READY!</span>}
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
                          onClick={() => void localParticipant.setCameraEnabled(!isCameraEnabled)}
                          title={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                          aria-label={isCameraEnabled ? '카메라 끄기' : '카메라 켜기'}
                        >
                          {isCameraEnabled ? CamOnIcon : CamOffIcon}
                        </button>
                        <button
                          type="button"
                          className={`lobby-tile__control${isMicrophoneEnabled ? '' : ' lobby-tile__control--off'}`}
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
                    <span
                      className={`lobby-tile__status${ready ? ' lobby-tile__status--ready' : ''}`}
                      title={ready ? '준비 완료' : '대기 중'}
                    >
                      {ready ? ReadyIcon : WaitingIcon}
                    </span>
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
                {/* 코스 도메인(백엔드) 연동 전까지 표시용 mock — 구성 변경은 그때 활성화 */}
                <button type="button" className="pap-pixel-btn lobby-btn-sm" disabled title="준비 중">
                  구성 변경
                </button>
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
            {/* 최대 7세트 — 이름 대신 아이콘 + 라운드 수 칩. 이름은 툴팁으로 */}
            <ol className="lobby-screen__sets">
              {MOCK_COURSE.map((set, i) => (
                <li
                  key={i}
                  className="lobby-screen__set"
                  title={`${String(i + 1).padStart(2, '0')} ${set.name} · ${set.rounds}라운드`}
                >
                  <span className="lobby-screen__set-icon">{set.icon}</span>
                  <span className="lobby-screen__set-rounds pap-pixel-title">{set.rounds}R</span>
                </li>
              ))}
            </ol>
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
              <button
                type="button"
                className="pap-pixel-btn pap-pixel-btn--coral"
                disabled={starting || !room}
                onClick={() => room && onStartGame(room.participants.map((p) => p.participantId))}
              >
                {starting ? '시작 중...' : '게임 시작'}
              </button>
            ) : (
              // 준비되면 눌린 채 고정된 라임 버튼으로 — 누르는 순간의 "철컥" UX.
              // (확정안은 준비 버튼 제거 예정 — 백엔드 ready 규칙 정리 전까지 임시)
              <button
                type="button"
                className={`pap-pixel-btn${
                  self?.ready ? ' lobby-screen__ready-btn--on' : ' pap-pixel-btn--teal'
                }`}
                disabled={readyPending || !self}
                onClick={() => void handleToggleReady()}
              >
                {readyPending ? '...' : self?.ready ? '준비 완료!' : '준비 하기'}
              </button>
            )}
          </div>
          {startError && <p className="lobby-screen__error">{startError}</p>}
        </aside>
      </div>

      {toast && <div className="pap-toast">{toast}</div>}
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
    </div>
  );
}
