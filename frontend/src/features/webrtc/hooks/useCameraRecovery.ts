import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocalParticipant } from '@livekit/components-react';
import { TrackEvent } from 'livekit-client';

/* 크롬 주소창의 카메라 아이콘으로 권한을 차단했다가 다시 허용해도 카메라가 안 돌아오는 문제를
 * 우리 쪽에서 복구한다. LiveKit SDK(livekit-client 2.20.1)가 스스로 복구하지 못하는 이유:
 *
 * LocalParticipant.handleTrackEnded는 권한이 denied면 "나중에 허용으로 바뀌면 되살리자"고
 * PermissionStatus.onchange를 걸어두는데, 그 직후 예외를 던져서 자신의 catch에서 track.mute()를
 * 한다. 그래서 나중에 onchange가 깨어나도 `if (!track.isMuted) restartTrack()` 조건이 false가 되어
 * 복구를 건너뛴다 — SDK가 자기가 mute해놓고 그 mute 때문에 복구를 거부하는 구조다.
 * 백업 경로도 없다: 트랙 재획득을 담당하는 handleAppVisibilityChanged는 첫 줄이
 * `if (!isMobile()) return;`이라 데스크톱에서는 아예 동작하지 않는다.
 *
 * 게임 화면(닌자/물건가져오기/몸으로말해요)에는 카메라 토글이 없어서(대기방에만 있다) 사용자가
 * 직접 복구할 수단도 없었다 — 그래서 자동 복구와 수동 버튼을 둘 다 제공한다.
 */

export interface CameraRecovery {
  /** 사용자 의도와 무관하게 카메라가 끊긴 상태. 직접 끈 경우는 여기 포함되지 않는다. */
  lost: boolean;
  /** 브라우저 권한이 차단된 상태 — 안내 문구를 "주소창에서 허용" 쪽으로 바꾸는 데 쓴다. */
  permissionDenied: boolean;
  /** 재획득 시도 중 (버튼 비활성화용) */
  recovering: boolean;
  /** 마지막 복구 실패 사유. 성공하면 null로 돌아간다. */
  error: string | null;
  /** 수동 복구. 권한 조회를 지원하지 않는 브라우저나 다른 앱이 장치를 점유한 경우의 유일한 수단. */
  retry: () => void;
}

export function useCameraRecovery(): CameraRecovery {
  const { localParticipant, isCameraEnabled, cameraTrack } = useLocalParticipant();
  const [lost, setLost] = useState(false);
  const [permissionDenied, setPermissionDenied] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 끊기기 직전에 "사용자가 카메라를 켜두려 했는가". 트랙이 죽으면 SDK가 곧 mute해서
  // isCameraEnabled가 false로 바뀌므로, 그 false를 사용자 의도로 오해하지 않도록 lost인 동안엔
  // 갱신을 멈춘다. ref로 두는 이유는 Ended 이벤트가 렌더 사이에 들어와서, 그 시점에 가장 최근
  // 렌더의 값을 읽어야 SDK의 mute 타이밍과 무관하게 의도가 보존되기 때문이다.
  const wantsCameraRef = useRef(isCameraEnabled);
  useEffect(() => {
    if (lost) return;
    wantsCameraRef.current = isCameraEnabled;
  }, [isCameraEnabled, lost]);

  const recover = useCallback(async () => {
    setRecovering(true);
    setError(null);
    try {
      // 끄고 다시 켠다. setCameraEnabled(true) 하나만 부르면, SDK가 아직 mute를 반영하지 않은
      // 상태(경합)에서는 "이미 켜져 있음"으로 판단해 no-op이 되어 죽은 트랙이 그대로 남는다.
      // 명시적으로 mute → unmute를 태우면 unmute가 LocalVideoTrack.restart()로 getUserMedia를
      // 새로 잡는 경로가 보장된다. 이미 muted면 첫 호출은 조용히 무시된다.
      await localParticipant.setCameraEnabled(false);
      await localParticipant.setCameraEnabled(true);
      setLost(false);
      setPermissionDenied(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '카메라를 다시 켜지 못했어요');
    } finally {
      setRecovering(false);
    }
  }, [localParticipant]);

  // 트랙이 의도와 무관하게 끝나는 것을 감지한다.
  // 사용자가 대기방 버튼으로 끈 경우엔 이 이벤트가 오지 않는다 — mute()가 부르는
  // MediaStreamTrack.stop()은 명세상 'ended' 이벤트를 발생시키지 않기 때문이다. 그래도
  // wantsCameraRef로 한 번 더 막아서, 향후 SDK가 동작을 바꿔도 오작동하지 않게 한다.
  useEffect(() => {
    const track = cameraTrack?.track;
    if (!track) return;

    const handleEnded = () => {
      if (!wantsCameraRef.current) return;
      setLost(true);
    };

    track.on(TrackEvent.Ended, handleEnded);
    return () => {
      track.off(TrackEvent.Ended, handleEnded);
    };
  }, [cameraTrack]);

  // 권한이 다시 허용되는 순간 자동 복구한다 — SDK가 못 하는 바로 그 지점이다.
  // devicechange는 일부러 듣지 않는다: 장치 목록이 바뀌었다고 카메라가 비었다는 뜻은 아니라서,
  // 실패하는 getUserMedia를 반복 호출하는 재시도 폭풍이 되기 쉽다. 그쪽은 수동 버튼에 맡긴다.
  useEffect(() => {
    if (!lost) return;

    let cancelled = false;
    let status: PermissionStatus | null = null;

    const handleChange = () => {
      if (cancelled || !status) return;
      const denied = status.state === 'denied';
      setPermissionDenied(denied);
      // 차단이 풀렸으면 곧바로 재획득한다. 아직 차단 중이면 안내만 바꾸고 기다린다.
      if (!denied) void recover();
    };

    navigator.permissions
      ?.query({ name: 'camera' })
      .then((result) => {
        if (cancelled) return;
        status = result;
        // 권한 문제가 아니라 다른 앱이 장치를 점유한 경우엔 이 시점에 이미 granted다 —
        // 한 번 시도해보고, 실패하면 error만 남기고 수동 버튼을 기다린다(자동 반복 없음).
        handleChange();
        result.addEventListener('change', handleChange);
      })
      .catch(() => {
        // Firefox/Safari는 'camera' 권한 조회를 지원하지 않는다. 자동 복구를 포기하고
        // 수동 버튼만 남긴다 — 안내 문구는 권한 차단을 단정하지 않는 쪽으로 나간다.
      });

    return () => {
      cancelled = true;
      status?.removeEventListener('change', handleChange);
    };
  }, [lost, recover]);

  const retry = useCallback(() => {
    void recover();
  }, [recover]);

  return { lost, permissionDenied, recovering, error, retry };
}
