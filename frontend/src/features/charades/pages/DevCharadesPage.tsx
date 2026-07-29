import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { enterRoom } from '../../room/lib/enterRoom';

// 개발 전용 진입로: 랜딩 → 닉네임 → 방 생성 클릭을 생략하고 곧장 대기방으로 들어간다.
// 코스(게임 선택/순서) 도메인이 아직 없어서 "게임 시작"이 닌자로 고정돼 있는데, 이 경로로 만든 방은
// ?autostart=charades를 달고 있어 VideoCallRoom의 startGame이 몸으로 말해요를 시작한다.
//
// fetch(/dev/fetch)와 달리 게임을 자동으로 시작하지는 않는다 — 몸으로 말해요는 3~4명이 필요하고
// 서버가 전원 준비를 검증하므로, 대기방에서 초대 링크로 2명 이상 더 들어온 뒤 방장이 눌러야 한다.
//
// App.tsx에서 import.meta.env.DEV일 때만 라우트가 등록되므로 프로덕션 빌드에는 존재하지 않는다.
export function DevCharadesPage() {
  const navigate = useNavigate();
  const startedRef = useRef(false); // StrictMode 이중 실행 가드
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    const nickname = `dev${Math.floor(Math.random() * 1000)}`;
    enterRoom({ mode: 'create', nickname })
      .then((roomId) => navigate(`/rooms/${roomId}?autostart=charades`, { replace: true }))
      .catch((err) =>
        setError(err instanceof Error ? err.message : '방 생성 실패 — 백엔드 확인'),
      );
  }, [navigate]);

  return (
    <main className="pap-plaza-screen">
      <p className="pap-pixel-title" style={{ color: 'var(--pap-ink)' }}>
        {error ?? '개발용 방 생성 중...'}
      </p>
    </main>
  );
}
