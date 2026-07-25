import { useState } from 'react';
import { useNavigate } from 'react-router';
import { EntryModal } from '../components/EntryModal';
import { ROOM_CODE_PATTERN } from '../lib/enterRoom';
import './EntryPage.css';

// 코드로 참여 1단계 — 참여 코드만 입력받는다. 랜딩 위 모달로 뜬다(LandingPage의 Outlet).
// 확정 플로우(인수인계 2026-07-24): 코드 입력과 닉네임 입력은 한 모달에 합치지 않고 단계별로 분리.
// 코드 실존 여부를 미리 확인하는 API는 없으므로 여기서는 형식만 검사하고,
// 실제 입장 실패(없는 방/정원 초과 등)는 닉네임 단계에서 백엔드 응답으로 안내한다.
export function JoinRoomPage() {
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const normalized = code.trim().toUpperCase();
    if (!ROOM_CODE_PATTERN.test(normalized)) {
      setError('참여 코드는 6자입니다.');
      return;
    }
    navigate(`/nickname?mode=join&code=${normalized}`);
  };

  return (
    <EntryModal onClose={() => navigate('/')}>
      <form className="entry__card pap-pixel-card" onSubmit={handleSubmit}>
        <h1 className="entry__title pap-pixel-title">코드로 참여</h1>
        <input
          className="pap-input entry__input entry__input--code"
          value={code}
          onChange={(event) => {
            setCode(event.target.value.toUpperCase());
            setError(null);
          }}
          maxLength={6}
          placeholder="ABC123"
          autoFocus
          aria-label="참여 코드"
        />
        {error && <p className="entry__error">{error}</p>}
        <div className="entry__actions">
          <button type="button" className="pap-pixel-btn" onClick={() => navigate('/')}>
            뒤로
          </button>
          <button type="submit" className="pap-pixel-btn pap-pixel-btn--primary">
            다음
          </button>
        </div>
      </form>
    </EntryModal>
  );
}
