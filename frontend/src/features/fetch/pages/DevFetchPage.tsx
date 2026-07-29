import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { enterRoom } from '../../room/lib/enterRoom';

// 개발 전용 원클릭 진입: 랜딩 → 닉네임 → 방 생성 → 게임 시작 클릭을 전부 생략하고
// 곧장 물건 가져오기 게임으로 들어간다 (RoomContent가 ?autostart=fetch를 보고 자동 시작).
// 제시어는 기본 랜덤이고, /dev/fetch?target=휴대폰 처럼 고정할 수 있다 (물건 없는 환경 테스트용).
// App.tsx에서 import.meta.env.DEV일 때만 라우트가 등록되므로 프로덕션 빌드에는 존재하지 않는다.
export function DevFetchPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const startedRef = useRef(false); // StrictMode 이중 실행 가드
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    const nickname = `dev${Math.floor(Math.random() * 1000)}`;
    // /dev/fetch에 붙은 파라미터(target, theme 등)를 게임 화면 URL로 그대로 넘긴다
    const forwarded = new URLSearchParams(searchParams);
    forwarded.set('autostart', 'fetch');
    enterRoom({ mode: 'create', nickname })
      .then((roomId) =>
        navigate(`/rooms/${roomId}?${forwarded.toString()}`, { replace: true }),
      )
      .catch((err) =>
        setError(err instanceof Error ? err.message : '방 생성 실패 — 백엔드 확인'),
      );
  }, [navigate, searchParams]);

  return (
    <main className="pap-plaza-screen">
      <p className="pap-pixel-title" style={{ color: 'var(--pap-ink)' }}>
        {error ?? '개발용 방 생성 중...'}
      </p>
    </main>
  );
}
