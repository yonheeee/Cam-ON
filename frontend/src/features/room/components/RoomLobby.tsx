import { useState } from 'react';
import { useRoomLobby } from '../hooks/useRoomLobby';
import './RoomLobby.css';

interface RoomLobbyProps {
  roomId: string;
  accessToken: string;
  participantId: string;
  onStartGame: (participantTokens: string[]) => void;
  starting: boolean;
  startError: string | null;
}

// 화상통화 화면(VideoCallRoom) 안에 얹혀서 게임 시작 전까지 떠 있는 대기방 패널이다.
// 참가자는 항상 닉네임으로만 표시한다 — participantId는 내부 식별/API 호출용이고 화면에 안 띄운다.
//
// 디자인 확정안(인수인계 2026-07-24) 반영:
// - 상단 참여 코드 + 코드 복사/링크 복사
// - 게임 시작은 방장 외 비활성화 (긴 안내문 대신 disabled 상태로 표현)
// - 환경설정 버튼 자리만 확보 (카메라/마이크/인식 테스트 — 미구현 placeholder)
// - "준비 완료" 버튼은 확정안에서 제거됐지만, 백엔드 ready 규칙과의 정리가 끝날 때까지 임시 유지
export function RoomLobby({
  roomId,
  accessToken,
  participantId,
  onStartGame,
  starting,
  startError,
}: RoomLobbyProps) {
  const { room, error, toggleReady } = useRoomLobby(roomId, accessToken);
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);

  if (error) {
    return <div className="room-lobby pap-pixel-card">방 정보를 불러오지 못했습니다: {error}</div>;
  }
  if (!room) return <div className="room-lobby pap-pixel-card">불러오는 중...</div>;

  const self = room.participants.find((p) => p.participantId === participantId);
  const isHost = participantId === room.hostParticipantId;

  const copy = async (kind: 'code' | 'link') => {
    // 초대 링크 형식은 백엔드 RoomInviteLinkGenerator({frontend}/rooms/join?code=)와 동일하게 맞춘다.
    const text =
      kind === 'code' ? room.roomCode : `${window.location.origin}/rooms/join?code=${room.roomCode}`;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(kind);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      // clipboard 접근이 막힌 환경(비 HTTPS 등)에서는 조용히 넘어간다 — 코드는 화면에 그대로 보인다.
    }
  };

  return (
    <div className="room-lobby pap-pixel-card">
      <header className="room-lobby__header">
        <span className="room-lobby__code-label">참여 코드</span>
        <span className="room-lobby__code pap-pixel-title">{room.roomCode}</span>
        <div className="room-lobby__copy-actions">
          <button type="button" className="pap-pixel-btn room-lobby__btn-sm" onClick={() => copy('code')}>
            {copied === 'code' ? '복사됨!' : '코드 복사'}
          </button>
          <button type="button" className="pap-pixel-btn room-lobby__btn-sm" onClick={() => copy('link')}>
            {copied === 'link' ? '복사됨!' : '링크 복사'}
          </button>
        </div>
      </header>

      <ul className="room-lobby__participants">
        {room.participants.map((p, index) => (
          <li key={p.participantId} className="room-lobby__participant">
            {/* 플레이어 대표색(1~4P)은 입장 순서 기준 */}
            <span className={`room-lobby__player-chip room-lobby__player-chip--${(index % 4) + 1}`} />
            <span className="room-lobby__nickname">{p.nickname}</span>
            {p.participantId === room.hostParticipantId && (
              <span className="room-lobby__host-badge">방장</span>
            )}
            <span
              className={`room-lobby__ready${p.ready ? ' room-lobby__ready--done' : ''}`}
            >
              {p.ready ? '준비완료' : '대기중'}
            </span>
          </li>
        ))}
      </ul>

      {self && (
        <button
          type="button"
          className="pap-pixel-btn room-lobby__btn-sm room-lobby__ready-toggle"
          onClick={() => toggleReady(!self.ready)}
        >
          {self.ready ? '준비 취소' : '준비 완료'}
        </button>
      )}

      <footer className="room-lobby__actions">
        <button
          type="button"
          className="pap-pixel-btn room-lobby__btn-sm"
          disabled
          title="카메라/마이크/인식 테스트 — 준비 중"
        >
          환경설정
        </button>
        <button
          type="button"
          className="pap-pixel-btn pap-pixel-btn--primary room-lobby__btn-sm"
          disabled={starting || !isHost}
          title={isHost ? undefined : '방장만 시작할 수 있어요'}
          onClick={() => onStartGame(room.participants.map((p) => p.participantId))}
        >
          {starting ? '시작 중...' : '게임 시작'}
        </button>
      </footer>
      {startError && <p className="room-lobby__error">{startError}</p>}
    </div>
  );
}
