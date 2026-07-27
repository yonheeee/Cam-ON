import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { SessionApiError } from '../../session/api/sessionApi';
import { RoomApiError } from '../api/roomApi';
import { EntryModal } from '../components/EntryModal';
import { enterRoom, NICKNAME_PATTERN, ROOM_CODE_PATTERN, type EnterMode } from '../lib/enterRoom';
import './EntryPage.css';

const PLAYER_OPTIONS = [2, 3, 4];

// 진입 플로우 마지막 단계 — 닉네임 입력 후 세션 발급 + 방 생성/입장을 한 번에 처리.
// 랜딩 위 모달로 뜬다(LandingPage의 Outlet).
//   방 만들기:   /nickname?mode=create
//   코드로 참여: /nickname?mode=join&code=ABC123 (JoinRoomPage 경유)
//   링크로 참여: /rooms/join?code=ABC123 → 여기로 리다이렉트
export function NicknamePage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [nickname, setNickname] = useState('');
  const [maxPlayers, setMaxPlayers] = useState(4);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const modeParam = searchParams.get('mode');
  const mode: EnterMode | null =
    modeParam === 'create' || modeParam === 'join' ? modeParam : null;
  const roomCode = (searchParams.get('code') ?? '').toUpperCase();

  // 잘못된 진입은 흐름의 올바른 시작점으로 돌려보낸다.
  if (mode === null) return <Navigate to="/" replace />;
  if (mode === 'join' && !ROOM_CODE_PATTERN.test(roomCode)) return <Navigate to="/join" replace />;

  const backTo = mode === 'join' ? '/join' : '/';

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!NICKNAME_PATTERN.test(nickname)) {
      setError('닉네임은 공백/기호 없이 1~8자여야 합니다.');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const roomId = await enterRoom({ mode, nickname, roomCode, maxPlayers });
      navigate(`/rooms/${roomId}`);
    } catch (err) {
      if (err instanceof RoomApiError && err.code === 'ROOM_NOT_FOUND') {
        // 형식은 맞지만 존재하지 않는 코드 — "6자입니다" 같은 형식 안내와 구분해서,
        // 코드를 잘못 쳤거나 방이 사라졌음을 인지할 수 있게 한다.
        setError(`코드 ${roomCode}에 해당하는 방을 찾을 수 없어요. 코드를 다시 확인해주세요.`);
      } else if (err instanceof SessionApiError || err instanceof RoomApiError) {
        setError(err.message);
      } else {
        setError(mode === 'create' ? '방 생성 실패' : '방 입장 실패');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    // 입장 처리 중에는 백드롭/ESC로 닫히지 않게 한다
    <EntryModal onClose={() => !submitting && navigate(backTo)}>
      <form className="entry__card pap-pixel-card" onSubmit={handleSubmit}>
        <h1 className="entry__title pap-pixel-title">
          {mode === 'create' ? '방 만들기' : '코드로 참여'}
        </h1>
        {mode === 'join' && (
          // 확정안: 코드 확인 후 안내는 "코드를 확인했어요." 정도로 짧게.
          <p className="entry__hint">코드를 확인했어요. ({roomCode})</p>
        )}
        <input
          className="pap-input entry__input"
          value={nickname}
          onChange={(event) => {
            setNickname(event.target.value);
            setError(null);
          }}
          maxLength={8}
          placeholder="닉네임 (1~8자)"
          autoFocus
          disabled={submitting}
          aria-label="닉네임"
        />
        {mode === 'create' && (
          <div className="entry__players">
            <span>인원</span>
            {PLAYER_OPTIONS.map((n) => (
              <button
                key={n}
                type="button"
                className={`pap-pixel-btn entry__players-btn${
                  maxPlayers === n ? ' entry__players-btn--selected' : ''
                }`}
                onClick={() => setMaxPlayers(n)}
                disabled={submitting}
              >
                {n}
              </button>
            ))}
          </div>
        )}
        {error && <p className="entry__error">{error}</p>}
        <div className="entry__actions">
          <button
            type="button"
            className="pap-pixel-btn"
            onClick={() => navigate(backTo)}
            disabled={submitting}
          >
            뒤로
          </button>
          <button type="submit" className="pap-pixel-btn pap-pixel-btn--primary" disabled={submitting}>
            {submitting ? '입장 중...' : mode === 'create' ? '방 만들기' : '입장하기'}
          </button>
        </div>
      </form>
    </EntryModal>
  );
}
