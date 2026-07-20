import { useEffect, useRef } from 'react';
import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { DrawingUtils, HandLandmarker } from '@mediapipe/tasks-vision';
import { useHandGestureRecognition } from '../hooks/useHandGestureRecognition';
import { SKILL_EFFECT_LABELS } from '../lib/labels';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../lib/gestureBroadcast';
import { useGestureBoardStore } from '../store/gestureBoardStore';
import './GesturePanel.css';

// 로컬 카메라 트랙에 손동작 인식(MediaPipe HandLandmarker + 포팅한 분류기 2개)을 붙여
// 스켈레톤과 판정 라벨을 보여주는 데모 패널. LiveKit의 VideoConference와는 별개로,
// 같은 카메라 트랙을 별도 <video>에 붙여서 돌린다 (거울모드 없이 원본 프레임 그대로).
// 판정 결과는 로컬 화면에 표시하는 동시에 데이터 채널로 브로드캐스트해서
// GestureBoard가 모든 참가자의 결과를 한 곳에 모아 보여줄 수 있게 한다.
export function GesturePanel() {
  const { cameraTrack, localParticipant } = useLocalParticipant();
  const { send } = useDataChannel(GESTURE_RESULT_TOPIC);
  const setEntry = useGestureBoardStore((state) => state.setEntry);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const track = cameraTrack?.track;

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !track) return;
    track.attach(video);
    return () => {
      track.detach(video);
    };
  }, [track]);

  const { results, ready, error } = useHandGestureRecognition(videoRef, Boolean(track));
  const latestEntryRef = useRef<GestureResultPayload>({
    identity: localParticipant.identity,
    handSignLabel: '-',
    fingerGestureLabel: '-',
  });

  useEffect(() => {
    const primary = results[0];
    const entry: GestureResultPayload = {
      identity: localParticipant.identity,
      handSignLabel: primary?.handSignLabel ?? '-',
      fingerGestureLabel: primary?.fingerGestureLabel ?? '-',
    };
    latestEntryRef.current = entry;
    setEntry(localParticipant.identity, entry);
  }, [results, localParticipant.identity, setEntry]);

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
        color: SKILL_EFFECT_LABELS.has(hand.handSignLabel) ? '#ffd400' : '#00e676',
        lineWidth: 3,
      });
      drawingUtils.drawLandmarks(hand.landmarks, { color: '#ffffff', radius: 3 });
    }
  }, [results]);

  return (
    <div className="gesture-panel">
      <h2>손동작 인식 (JS 포팅)</h2>
      {!ready && !error && <p>모델 로딩 중...</p>}
      {error && <p className="gesture-panel__error">모델 로딩 실패: {error}</p>}
      <div className="gesture-panel__stage">
        <video ref={videoRef} autoPlay playsInline muted />
        <canvas ref={canvasRef} />
      </div>
      <ul className="gesture-panel__results">
        {results.length === 0 && <li>손이 인식되지 않음</li>}
        {results.map((hand) => (
          <li key={hand.handedness}>
            {hand.handedness}: 손모양 <strong>{hand.handSignLabel}</strong> / 움직임{' '}
            <strong>{hand.fingerGestureLabel}</strong>
            {SKILL_EFFECT_LABELS.has(hand.handSignLabel) && <span className="gesture-panel__skill"> ⚡ 스킬 발동</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}
