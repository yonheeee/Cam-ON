import { useEffect, useRef } from 'react';
import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { DrawingUtils, HandLandmarker } from '@mediapipe/tasks-vision';
import { useHandGestureRecognition } from '../hooks/useHandGestureRecognition';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../lib/gestureBroadcast';
import { useGestureBoardStore } from '../store/gestureBoardStore';
import './GesturePanel.css';

// 로컬 카메라 트랙에 손동작 인식(MediaPipe HandLandmarker + 포팅한 9클래스 양손 조합 분류기)을
// 붙여 스켈레톤과 판정 스킬을 보여주는 데모 패널. LiveKit의 VideoConference와는 별개로,
// 같은 카메라 트랙을 별도 <video>에 붙여서 돌린다. 원본 <video>는 화면에 표시하지 않고
// (display: none) 오직 프레임 소스로만 쓴다 — 실제로 보이는 화면은 useHandGestureRecognition이
// 내부에서 좌우반전해 그리는 mirrorCanvas다(이유는 훅 파일 주석 참고: MediaPipe 좌우 판정을
// 학습 파이프라인과 맞추기 위해 detection 자체를 반전 프레임에서 수행하고, 그 캔버스를
// 화면에도 그대로 재사용해서 거울모드 표시와 스켈레톤 정렬을 CSS 트릭 없이 맞춘다).
// 판정 결과는 로컬 화면에 표시하는 동시에 데이터 채널로 브로드캐스트해서
// GestureBoard가 모든 참가자의 결과를 한 곳에 모아 보여줄 수 있게 한다.
interface GesturePanelProps {
  /**
   * 'panel'(기본): 우하단 고정 미리보기 박스(반전 프레임 + 스켈레톤 + 판정 목록).
   * 'overlay': 스켈레톤 캔버스만 렌더링해 호출부(내 캠 타일)에 얹는다. 인식/브로드캐스트
   * 로직은 완전히 동일하다 — 미리보기 UI만 빠진다.
   *
   * 좌표계: 랜드마크는 반전(mirror)된 프레임에서 뽑히고, LiveKit도 로컬 카메라 비디오를
   * rotateY(180deg)로 반전해 보여준다(.lk-participant-media-video[data-lk-local-participant]).
   * 둘이 같은 반전 공간이라 스켈레톤을 그대로 겹치면 맞는다. 캔버스는 비디오의 natural 크기로
   * 두고 CSS에서 object-fit: cover를 걸어 LiveKit의 크롭과 동일하게 잘린다.
   */
  variant?: 'panel' | 'overlay';
  /**
   * 인식을 돌릴지. false면 MediaPipe 프레임 루프와 데이터 채널 브로드캐스트를 모두 멈춘다 —
   * 닌자에서 탈락한 참가자용. 판정에 쓰이지도 않을 추론을 매 프레임 돌리는 건 CPU만 태우고,
   * 초당 2회 브로드캐스트도 그대로 나가서 낭비다. 기본값 true.
   */
  active?: boolean;
}

export function GesturePanel({ variant = 'panel', active = true }: GesturePanelProps = {}) {
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

  const { results, combo, ready, error, mirrorCanvasRef } = useHandGestureRecognition(
    videoRef,
    active && Boolean(track),
  );

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
    // 인식을 안 돌리면(탈락 등) 보낼 것도 없다 — 주기 전송을 멈춘다. 다만 마지막으로 한 번
    // "손 없음"을 알려서 다른 참가자 보드에 내 직전 콤보가 굳은 채 남지 않게 한다.
    if (!active) {
      const cleared: GestureResultPayload = {
        identity: localParticipant.identity,
        comboLabel: null,
        confidence: 0,
      };
      latestEntryRef.current = cleared;
      send(new TextEncoder().encode(JSON.stringify(cleared)), {
        topic: GESTURE_RESULT_TOPIC,
        reliable: true,
      }).catch(() => {});
      return;
    }
    const interval = setInterval(() => {
      const serialized = JSON.stringify(latestEntryRef.current);
      send(new TextEncoder().encode(serialized), { topic: GESTURE_RESULT_TOPIC, reliable: true }).catch(() => {});
    }, 500);
    return () => clearInterval(interval);
  }, [active, send, localParticipant.identity]);

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

  // 오버레이 모드 — 프레임 소스용 <video>(숨김)와 스켈레톤 캔버스만. 반전 프레임 캔버스는
  // 삽입하지 않는다(LiveKit이 이미 같은 카메라를 반전해 그리고 있어서 겹치면 두 겹이 된다).
  if (variant === 'overlay') {
    return (
      <>
        <video ref={videoRef} autoPlay playsInline muted style={{ display: 'none' }} />
        <canvas ref={canvasRef} className="gesture-skeleton" aria-hidden />
      </>
    );
  }

  return (
    <div className="gesture-panel">
      <h2>손동작 인식 (JS 포팅 · v1 9클래스)</h2>
      {!ready && !error && <p>모델 로딩 중...</p>}
      {error && <p className="gesture-panel__error">모델 로딩 실패: {error}</p>}
      <div className="gesture-panel__stage" ref={stageRef}>
        <video ref={videoRef} autoPlay playsInline muted style={{ display: 'none' }} />
        <canvas ref={canvasRef} />
      </div>
      <ul className="gesture-panel__results">
        {results.length < 2 && <li>양손이 다 잡혀야 스킬이 판정됩니다 (인식된 손 {results.length}개)</li>}
        {combo.label && (
          <li>
            스킬 <strong>{combo.label}</strong> ({(combo.confidence * 100).toFixed(0)}%
            {combo.handGap !== null && `, 손 거리 ${combo.handGap.toFixed(1)}`})
            <span className="gesture-panel__skill"> ⚡ 스킬 발동</span>
          </li>
        )}
        {/* 모양은 맞았지만 손 사이 거리 조건에서 걸러진 경우 — 왜 인식이 안 되는지 알려준다.
            표시되는 숫자는 손바닥 길이 단위이고, 그대로 handProximity.ts의 라벨별 한계 튜닝에 쓴다. */}
        {!combo.label && combo.rawLabel && combo.handGap !== null && (
          <li>
            <strong>{combo.rawLabel}</strong> 모양은 맞지만 두 손이 멀어요 (거리 {combo.handGap.toFixed(1)} / 허용{' '}
            {combo.handGapLimit?.toFixed(1)} 손바닥)
          </li>
        )}
      </ul>
    </div>
  );
}
