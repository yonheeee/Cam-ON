import { Outlet, useNavigate } from 'react-router';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import './LandingPage.css';

export function LandingPage() {
  const navigate = useNavigate();

  return (
    <main className="landing" aria-label="CAM, ON! 메인">
      <BackgroundMusic
        source="/assets/sounds/party-lobby-pixel.mp3"
        className="landing__music-toggle"
      />

      <div className="landing__hero">
        <div className="landing__brand">
          <img
            className="landing__logo"
            src="/assets/cam-on-logo-v3.png"
            alt="CAM, ON!"
          />
        </div>

        <p className="landing__supporting-copy">
          방을 만들거나 코드로 바로 참여하세요.
        </p>

        <div className="landing__actions">
          <button
            type="button"
            className="landing__button landing__button--primary"
            onClick={() => navigate('/players')}
          >
            방 만들기
          </button>
          <button
            type="button"
            className="landing__button landing__button--secondary"
            onClick={() => navigate('/join')}
          >
            코드로 참여
          </button>
        </div>
      </div>

      <Outlet />
    </main>
  );
}
