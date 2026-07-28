import { useEffect, useRef } from 'react';
import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { DrawingUtils, HandLandmarker } from '@mediapipe/tasks-vision';
import { useHandGestureRecognition } from '../hooks/useHandGestureRecognition';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../lib/gestureBroadcast';
import { useGestureBoardStore } from '../store/gestureBoardStore';
import './GesturePanel.css';

// 로컬 카메라 트랙에 손동작 인식(MediaPipe HandLandmarker + 포팅한 9클래스 양손 조합 분류기)을
// 붙여 스켈레톤을 그리는 패널. NinjaGamePanel이 이 컴포넌트를 "내 캠 칸" 안에 직접 렌더링해서
// 실제 캠 화면 위에 스켈레톤이 겹쳐 보이게 한다(다른 참가자는 손 좌표 자체가 서버로 전송되지
// 않으므로 스켈레톤을 그릴 수 없다 — 나만 표시).
// 같은 카메라 트랙을 별도 <video>에 붙여서 돌린다. 원본 <video>는 화면에 표시하지 않고
// (display: none) 오직 프레임 소스로만 쓴다 — 실제로 보이는 화면은 useHandGestureRecognition이
// 내부에서 좌우반전해 그리는 mirrorCanvas다(이유는 훅 파일 주석 참고: MediaPipe 좌우 판정을
// 학습 파이프라인과 맞추기 위해 detection 자체를 반전 프레임에서 수행하고, 그 캔버스를
// 화면에도 그대로 재사용해서 거울모드 표시와 스켈레톤 정렬을 CSS 트릭 없이 맞춘다).
// 판정 결과는 데이터 채널로도 브로드캐스트해서 다른 참가자의 스킬 라벨/신뢰도는 공유한다
// (좌표 자체는 공유하지 않음).
export function GesturePanel() {
  const { cameraTrack, localParticipant } = useLocalParticipant();
  const { send } = useDataChannel(GESTURE_RESULT_TOPIC);
  const setEntry = useGestureBoardStore((state) => state.setEntry);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  const track = cameraTrack?.track;

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !track) return;
    track.attach(video);
    return () => {
      track.detach(video);
    };
  }, [track]);

  const { results, combo, mirrorCanvasRef } = useHandGestureRecognition(videoRef, Boolean(track));

  // 훅이 내부적으로 소유한(React 트리 밖에서 생성된) mirrorCanvas를 스테이지의 첫 번째
  // 자식으로 직접 삽입한다 — 스켈레톤 캔버스보다 먼저 와야 그 아래(배경)에 깔린다.
  useEffect(() => {
    const stage = stageRef.current;
    const mirrorCanvas = mirrorCanvasRef.current;
    if (!stage || !mirrorCanvas) return;
    mirrorCanvas.className = 'gesture-panel__mirror-canvas';
    stage.insertBefore(mirrorCanvas, stage.firstChild);
    return () => {
      if (mirrorCanvas.parentElement === stage) stage.removeChild(mirrorCanvas);
    };
  }, [mirrorCanvasRef]);
  const latestEntryRef = useRef<GestureResultPayload>({
    identity: localParticipant.identity,
    comboLabel: null,
    confidence: 0,
  });

  useEffect(() => {
    const entry: GestureResultPayload = {
      identity: localParticipant.identity,
      comboLabel: combo.label,
      confidence: combo.confidence,
    };
    latestEntryRef.current = entry;
    setEntry(localParticipant.identity, entry);
  }, [combo, localParticipant.identity, setEntry]);

  // 인식 루프는 초당 30~60번 도는데, 그 속도로 데이터 채널 전송을 시도하면
  // (특히 연결 초기 실패 시 매 프레임 재시도되면서) negotiate가 과도하게 몰려
  // "PC manager is closed" 에러가 나며 데이터 채널이 아예 못 열리는 걸 확인했다.
  // 그래서 실제 네트워크 전송은 별도 타이머로 최대 초당 2회로 제한한다.
  // 값이 바뀔 때만 보내는 dedup은 일부러 안 한다 — 늦게 들어온 참가자의 구독 채널이
  // 아직 안 열렸을 때 보낸 메시지는 유실되는데, 값이 그대로면(예: 계속 손 미인식)
  // 다시는 재전송되지 않아 영원히 "대기 중"으로 남는 문제가 있었다.
  useEffect(() => {
    const interval = setInterval(() => {
      const serialized = JSON.stringify(latestEntryRef.current);
      send(new TextEncoder().encode(serialized), { topic: GESTURE_RESULT_TOPIC, reliable: true }).catch(() => {});
    }, 500);
    return () => clearInterval(interval);
  }, [send]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const video = videoRef.current;
    if (!canvas || !video || video.videoWidth === 0) return;

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const drawingUtils = new DrawingUtils(ctx);
    for (const hand of results) {
      drawingUtils.drawConnectors(hand.landmarks, HandLandmarker.HAND_CONNECTIONS, {
        color: combo.label ? '#ffd400' : '#00e676',
        lineWidth: 3,
      });
      drawingUtils.drawLandmarks(hand.landmarks, { color: '#ffffff', radius: 3 });
    }
  }, [results, combo.label]);

  return (
    <div className="gesture-panel__stage" ref={stageRef}>
      <video ref={videoRef} autoPlay playsInline muted style={{ display: 'none' }} />
      <canvas ref={canvasRef} />
    </div>
  );
}
