import './PixelConfirmModal.css';

interface PixelConfirmModalProps {
  title: string;
  message?: string;
  confirmLabel: string;
  /** 없으면 확인 버튼만 있는 알림형 팝업 */
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel?: () => void;
  /** 확인 버튼 강조색 — 위험 동작(방 나가기 등)은 coral */
  tone?: 'default' | 'danger';
}

// 확인/알림 공용 픽셀 팝업. 디자인 확정안: 방 나가기·로고 클릭은 항상 확인 팝업을 거친다.
// RoomClosedPage도 페이지가 아니라 이 팝업 형태로 쓴다.
export function PixelConfirmModal({
  title,
  message,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  tone = 'default',
}: PixelConfirmModalProps) {
  return (
    <div className="pap-modal-backdrop">
      <div className="pap-modal">
        <div className="confirm-modal pap-pixel-card">
          <h2 className="confirm-modal__title pap-pixel-title">{title}</h2>
          {message && <p className="confirm-modal__message">{message}</p>}
          <div className="confirm-modal__actions">
            {cancelLabel && onCancel && (
              <button type="button" className="pap-pixel-btn" onClick={onCancel}>
                {cancelLabel}
              </button>
            )}
            <button
              type="button"
              className={`pap-pixel-btn ${
                tone === 'danger' ? 'pap-pixel-btn--coral' : 'pap-pixel-btn--primary'
              }`}
              onClick={onConfirm}
            >
              {confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
