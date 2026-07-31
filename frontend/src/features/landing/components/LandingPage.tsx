import { useState } from 'react';
import { Outlet, useNavigate } from 'react-router';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import { consumeSessionExpiredNotice } from '../../session/lib/sessionExpiry';
import './LandingPage.css';

export function LandingPage() {
  const navigate = useNavigate();
  // 세션이 죽어서(토큰 만료·백엔드 재시작) 방에서 튕겨 나온 경우, 왜 첫 화면인지 알려준다.
  // 안내는 한 번만 읽고 지워지므로 이후 이동/새로고침에는 남지 않는다. useState 초기화 함수로
  // 읽어서 StrictMode의 이중 렌더에도 표시가 사라지지 않게 한다.
  const [sessionExpired] = useState(consumeSessionExpiredNotice);

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

        {sessionExpired && (
          <p className="landing__notice" role="status">
            연결이 만료되어 방에서 나왔어요. 닉네임을 다시 입력하고 참여해 주세요.
          </p>
        )}

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
