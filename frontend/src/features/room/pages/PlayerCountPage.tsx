import { useState } from 'react';
import { useNavigate } from 'react-router';
import { EntryModal } from '../components/EntryModal';
import './EntryPage.css';

const PLAYER_OPTIONS = [2, 3, 4] as const;

export function PlayerCountPage() {
  const navigate = useNavigate();
  const [maxPlayers, setMaxPlayers] = useState<(typeof PLAYER_OPTIONS)[number] | null>(null);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (maxPlayers === null) return;
    navigate(`/nickname?mode=create&players=${maxPlayers}`);
  };

  return (
    <EntryModal onClose={() => navigate('/')}>
      <form className="entry__card entry__card--players" onSubmit={handleSubmit}>
        <header className="entry__header">
          <h1 className="entry__title">방 인원 선택</h1>
          <button
            type="button"
            className="entry__close"
            aria-label="닫기"
            onClick={() => navigate('/')}
          >
            ×
          </button>
        </header>

        <p className="entry__description">함께 플레이할 인원을 선택해주세요.</p>

        <div className="entry__player-options" aria-label="방 최대 인원">
          {PLAYER_OPTIONS.map((count) => (
            <button
              key={count}
              type="button"
              className={`entry__player-option${
                maxPlayers === count ? ' entry__player-option--selected' : ''
              }`}
              aria-pressed={maxPlayers === count}
              onClick={() => setMaxPlayers(count)}
            >
              {count}명
            </button>
          ))}
        </div>

        <button
          type="submit"
          className="entry__submit"
          disabled={maxPlayers === null}
        >
          다음
        </button>
      </form>
    </EntryModal>
  );
}
