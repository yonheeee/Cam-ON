import { Outlet, useNavigate } from 'react-router';
import { BackgroundMusic } from '../../sound/components/BackgroundMusic';
import './LandingPage.css';

// 메인(랜딩) — Pixel Arcade Plaza 테마.
// 디자인 확정안(인수인계 2026-07-24): 로고 + "방 만들기" / "코드로 참여"만 중심에 두고,
// 상단 내비게이션 바와 AI 홍보성 카피는 넣지 않는다.
//
// 라우트 레이아웃도 겸한다: /join(코드 입력), /nickname(닉네임)은 별도 화면 전환이 아니라
// 이 랜딩 위에 모달(팝업)로 뜬다(<Outlet/>). URL은 유지되므로 초대 링크·새로고침·뒤로가기가
// 전부 정상 동작한다. (확정안: "코드 입력과 닉네임 입력은 한 모달에 합치지 않고 단계별로 분리")
//
// 배경 모션(인물/구름/깃발 레이어)은 데모 프로젝트에서 추후 이식 — 지금은 정적 배경만.
export function LandingPage() {
  const navigate = useNavigate();

  return (
    <main className="pap-plaza-screen landing">
      <BackgroundMusic
        source="/assets/sounds/party-lobby-pixel.mp3"
        className="landing__music-toggle"
      />
      <img
        className="landing__logo pap-pixel-img"
        src="/assets/cam-on-logo.png"
        alt="CAM, ON!"
      />
      <div className="landing__actions">
        <button
          type="button"
          className="pap-pixel-btn pap-pixel-btn--primary landing__cta"
          onClick={() => navigate('/nickname?mode=create')}
        >
          방 만들기
        </button>
        <button
          type="button"
          className="pap-pixel-btn landing__cta"
          onClick={() => navigate('/join')}
        >
          코드로 참여
        </button>
      </div>
      {/* /join, /nickname 모달이 여기에 뜬다 */}
      <Outlet />
    </main>
  );
}
