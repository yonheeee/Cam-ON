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
      <div className="landing__scene" aria-hidden>
        <img
          className="landing__sprite landing__sprite--balloon-red"
          src="/assets/landing-balloon-red.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--balloon-blue"
          src="/assets/landing-balloon-blue.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--visitor-girl"
          src="/assets/landing-visitor-girl.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--visitor-boy"
          src="/assets/landing-visitor-boy.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--dancer-left"
          src="/assets/landing-dancer-left.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--dancer-center"
          src="/assets/landing-dancer-center.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--dancer-right"
          src="/assets/landing-dancer-right.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--spectator-left"
          src="/assets/landing-spectator-left.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--spectator-right"
          src="/assets/landing-spectator-right.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--heart-left"
          src="/assets/landing-heart-left.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--heart-right"
          src="/assets/landing-heart-right.png"
          alt=""
          draggable={false}
        />
        <img
          className="landing__sprite landing__sprite--heart-bubble"
          src="/assets/landing-heart-bubble.png"
          alt=""
          draggable={false}
        />
      </div>

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
            // 확정: 인원 선택 단계를 없애고 모든 방은 4인 정원으로 만든다.
            // 자리가 비어도 현재 인원 전원이 준비하면 시작 가능(서버 검증도 현재 인원 기준).
            onClick={() => navigate('/nickname?mode=create&players=4')}
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
