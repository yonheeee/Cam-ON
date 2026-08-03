import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { EntryModal } from '../components/EntryModal';
import { ROOM_CODE_PATTERN } from '../lib/enterRoom';
import './EntryPage.css';

const ROOM_NOT_FOUND_MESSAGE = '코드가 틀렸거나 존재하지 않는 방이에요.';

export function JoinRoomPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const initialCode = (searchParams.get('code') ?? '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 6);
  const initialError =
    searchParams.get('error') === 'room-not-found' ? ROOM_NOT_FOUND_MESSAGE : null;
  const [code, setCode] = useState(initialCode);
  const [error, setError] = useState<string | null>(initialError);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const normalized = code.trim().toUpperCase();

    if (!ROOM_CODE_PATTERN.test(normalized)) {
      setError('참여 코드 6자리를 확인해주세요.');
      return;
    }

    navigate(`/nickname?mode=join&code=${normalized}`);
  };

  return (
    <EntryModal onClose={() => navigate('/')}>
      <form className="entry__card" onSubmit={handleSubmit}>
        <header className="entry__header">
          <h1 className="entry__title">참여 코드 입력</h1>
          <button
            type="button"
            className="entry__close"
            aria-label="닫기"
            onClick={() => navigate('/')}
          >
            ×
          </button>
        </header>

        <label className="entry__field">
          <span className="entry__label">참여 코드 6자리</span>
          <input
            className={`entry__input entry__input--code${error ? ' entry__input--error' : ''}`}
            value={code}
            onChange={(event) => {
              setCode(
                event.target.value
                  .toUpperCase()
                  .replace(/[^A-Z0-9]/g, '')
                  .slice(0, 6),
              );
              setError(null);
            }}
            maxLength={6}
            placeholder="7K2P9Q"
            autoFocus
            autoComplete="off"
            aria-label="참여 코드"
            aria-invalid={Boolean(error)}
          />
        </label>

        <button
          type="submit"
          className="entry__submit"
          disabled={code.length !== 6}
        >
          다음
        </button>

        <p className={`entry__feedback${error ? ' entry__feedback--error' : ''}`}>
          {error ?? '초대받은 참여 코드를 입력해주세요.'}
        </p>
      </form>
    </EntryModal>
  );
}
