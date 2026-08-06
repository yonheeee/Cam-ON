import { useCameraRecovery } from '../hooks/useCameraRecovery';
import './CameraRecoveryBanner.css';

/* 카메라가 의도와 무관하게 끊겼을 때만 뜨는 배너.
 *
 * LiveKitRoom 컨텍스트 안에서 화면 전환(대기방 ↔ 게임 3종 ↔ 결과)과 무관하게 한 번만 마운트한다 —
 * 카메라가 끊기는 사건은 특정 게임의 문제가 아니고, 특히 게임 화면에는 카메라 토글이 없어서
 * 여기가 유일한 복구 입구다.
 *
 * 위치는 fixed다. 게임 화면들이 각자 전체화면(.camon-stage 등)을 차지하고 자기 안에서 z-index를
 * 쌓으므로, 어느 화면 위에서든 보이게 하려면 문서 기준으로 띄워야 한다. */
export function CameraRecoveryBanner() {
  const { lost, permissionDenied, recovering, error, retry } = useCameraRecovery();

  if (!lost) return null;

  return (
    <div className="camera-recovery" role="alert" aria-live="assertive">
      <p className="camera-recovery__text">
        {permissionDenied
          ? '카메라가 차단됐어요. 주소창의 카메라 아이콘에서 “허용”으로 바꾸면 자동으로 다시 켜집니다.'
          : '카메라 연결이 끊어졌어요. 다른 앱이 카메라를 쓰고 있는지 확인해 주세요.'}
      </p>
      {error && <p className="camera-recovery__error">{error}</p>}
      {/* 권한 차단 상태에서도 버튼을 남긴다 — 권한 조회를 지원하지 않는 브라우저나 change 이벤트를
          놓친 경우에 사용자가 직접 빠져나올 방법이 필요하다. */}
      <button
        type="button"
        className="camera-recovery__retry pap-pixel-btn"
        onClick={retry}
        disabled={recovering}
      >
        {recovering ? '다시 켜는 중…' : '카메라 다시 켜기'}
      </button>
    </div>
  );
}
