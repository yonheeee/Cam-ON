import type { ReactNode } from 'react';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import './RoomTopBar.css';

interface RoomTopBarProps {
  musicSource: string;
  onRequestLeave: () => void;
  className?: string;
  center?: ReactNode;
}

function LeaveRoomIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden>
      <path d="M10 4H5v16h5" />
      <path d="M14 8l4 4-4 4M18 12H9" />
    </svg>
  );
}

export function RoomTopBar({ musicSource, onRequestLeave, className = '', center }: RoomTopBarProps) {
  return (
    <header className={`room-topbar ${className}`}>
      <button
        type="button"
        className="room-topbar__logo-button"
        onClick={onRequestLeave}
        aria-label="방 나가기 확인"
      >
        <img className="room-topbar__logo" src="/assets/cam-on-logo-v3.png" alt="CAM, ON!" />
      </button>

      {center && <div className="room-topbar__center">{center}</div>}

      <div className="room-topbar__actions">
        <BackgroundMusic source={musicSource} className="room-topbar__sound" />
        <button
          type="button"
          className="room-topbar__leave pap-pixel-btn pap-pixel-btn--coral"
          onClick={onRequestLeave}
          data-button-sound="cancel"
          aria-label="방 나가기"
          title="방 나가기"
        >
          <LeaveRoomIcon />
        </button>
      </div>
    </header>
  );
}
