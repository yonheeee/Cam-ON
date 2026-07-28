import { useEffect } from 'react';

interface EntryModalProps {
  /** 백드롭 클릭·ESC로 닫을 때 호출 (보통 navigate로 이전 단계 이동) */
  onClose: () => void;
  children: React.ReactNode;
}

// 진입 플로우(/join, /nickname)가 랜딩 위에 팝업처럼 뜨게 하는 공용 래퍼.
// 라우트는 그대로 유지되고(route-modal 패턴) 표현만 모달이다.
export function EntryModal({ onClose, children }: EntryModalProps) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div
      className="pap-modal-backdrop"
      onClick={(event) => {
        // 카드 내부 클릭은 닫지 않는다 — 백드롭 자체를 클릭했을 때만
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="pap-modal">{children}</div>
    </div>
  );
}
