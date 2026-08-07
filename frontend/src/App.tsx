import { Navigate, Route, Routes, useSearchParams } from 'react-router';
import { LandingPage } from './features/landing/components/LandingPage';
import { SetResultPreview } from './features/course/components/SetResultPreview';
import { CourseResultPreview } from './features/course/components/CourseResultPreview';
import { JoinRoomPage } from './features/room/pages/JoinRoomPage';
import { NicknamePage } from './features/room/pages/NicknamePage';
import { RoomPage } from './features/room/pages/RoomPage';
import { ButtonSounds } from './features/sound/components/ButtonSounds';

// 초대 링크(백엔드 RoomInviteLinkGenerator가 만드는 {frontend}/rooms/join?code=XXXXXX) 진입점.
// 확정 플로우: 링크로 참여 → (코드 입력 생략) → 닉네임 입력 → 대기방.
function InviteRedirect() {
  const [searchParams] = useSearchParams();
  const code = (searchParams.get('code') ?? '').toUpperCase();
  // 코드가 없거나 형식이 이상하면 코드 입력 화면에서 직접 넣게 한다.
  return <Navigate to={code ? `/nickname?mode=join&code=${code}` : '/join'} replace />;
}

function App() {
  return (
    <>
      <ButtonSounds />
      <Routes>
        {/* 랜딩이 레이아웃을 겸한다 — /join, /nickname은 랜딩 위에 모달(팝업)로 렌더링 */}
        <Route path="/" element={<LandingPage />}>
          <Route path="join" element={<JoinRoomPage />} />
          {/* 확정: 인원 선택 폐지(전 방 4인 고정). 북마크·뒤로가기로 진입하면 닉네임으로 보낸다. */}
          <Route path="players" element={<Navigate to="/nickname?mode=create&players=4" replace />} />
          <Route path="nickname" element={<NicknamePage />} />
        </Route>
        {/* 개발 전용: 세트 중간 결과 화면 배치 확인 */}
        {import.meta.env.DEV && <Route path="/dev/set-result" element={<SetResultPreview />} />}
        {/* 개발 전용: 코스 최종 결과 화면 배치 확인 */}
        {import.meta.env.DEV && (
          <Route path="/dev/course-result" element={<CourseResultPreview />} />
        )}
        {/* 정적 세그먼트가 :roomId보다 우선 매칭되므로 /rooms/join이 RoomPage에 잡히지 않는다 */}
        <Route path="/rooms/join" element={<InviteRedirect />} />
        <Route path="/rooms/:roomId" element={<RoomPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}

export default App;
