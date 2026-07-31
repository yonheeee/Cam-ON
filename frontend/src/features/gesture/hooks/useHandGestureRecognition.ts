import { useEffect, useRef, useState, type RefObject } from 'react';
import { FilesetResolver, HandLandmarker, type NormalizedLandmark } from '@mediapipe/tasks-vision';
import { calcLandmarkList, preProcessLandmark, combineTwoHandLandmarks } from '../lib/landmarkPreprocessing';
import { classifyKeyPoint } from '../lib/keypointClassifier';
import { HandLandmarkSmoother } from '../lib/oneEuroFilter';

const WASM_BASE = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm';
const MODEL_URL =
  'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task';
// app.py의 mp.solutions.hands 기본 인자(min_detection_confidence=0.7, min_tracking_confidence=0.5)와 맞춤.
const MIN_HAND_DETECTION_CONFIDENCE = 0.7;
const MIN_TRACKING_CONFIDENCE = 0.5;
// app.py의 combo_sign_history = deque(maxlen=5) — 프레임 하나짜리 오인식(손 떨림 등)에
// 흔들리지 않도록 최근 5프레임의 다수결로 안정화한다.
const STABILIZE_WINDOW = 5;

export interface HandGestureResult {
  handedness: 'Left' | 'Right';
  landmarks: NormalizedLandmark[];
}

export interface ComboGestureResult {
  label: string | null; // 양손이 다 잡혀야만 값이 채워진다 (한 손이면 null)
  confidence: number;
}

function mostCommon(values: string[]): string {
  const counts = new Map<string, number>();
  let best = values[0];
  let bestCount = 0;
  for (const v of values) {
    const count = (counts.get(v) ?? 0) + 1;
    counts.set(v, count);
    if (count > bestCount) {
      bestCount = count;
      best = v;
    }
  }
  return best;
}

// app.py는 매 프레임 cv.flip(image, 1)로 좌우반전한 뒤에 hands.process()를 호출하고,
// 그 반전된 프레임 기준 좌표로 학습 데이터를 모았다. 그래서 detection도 반전 프레임에서
// 해야 한다는 것까진 맞았는데(원본을 그대로 넣으면 어떤 포즈든 sailor_moon으로 쏠린다),
// 실제로 라이브 카메라로 snake 포즈를 잡고 로그를 찍어 확인해보니(2026-07-22) 그것만으론
// 부족했다: 같은 반전 프레임을 넣어도 @mediapipe/tasks-vision의 HandLandmarker가 내놓는
// Left/Right 라벨이 Python의 mediapipe.solutions.hands(레거시 Solutions API)와 정반대
// 관례였다 — normal(Left+Right 순서)은 sailor_moon 90%+, swapped(Right+Left)는 snake
// 99~100%로 나옴. 그래서 여기서 라벨을 반전시켜서 combine_two_hand_landmarks의
// Left+Right 이어붙이는 순서(코드상 이름 그대로)가 실제로는 학습 때와 같은 물리적
// 손-슬롯 매핑이 되도록 맞춘다.
function correctHandedness(taskVisionLabel: 'Left' | 'Right'): 'Left' | 'Right' {
  return taskVisionLabel === 'Left' ? 'Right' : 'Left';
}

export function useHandGestureRecognition(videoRef: RefObject<HTMLVideoElement | null>, active: boolean) {
  const [results, setResults] = useState<HandGestureResult[]>([]);
  const [combo, setCombo] = useState<ComboGestureResult>({ label: null, confidence: 0 });
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const landmarkerRef = useRef<HandLandmarker | null>(null);
  const mirrorCanvasRef = useRef<HTMLCanvasElement>(document.createElement('canvas'));
  const smootherRef = useRef(new HandLandmarkSmoother(1.0, 0.3, 1.0));
  const stabilizeWindowRef = useRef<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    FilesetResolver.forVisionTasks(WASM_BASE)
      .then((vision) =>
        HandLandmarker.createFromOptions(vision, {
          baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
          runningMode: 'VIDEO',
          numHands: 2,
          minHandDetectionConfidence: MIN_HAND_DETECTION_CONFIDENCE,
          minTrackingConfidence: MIN_TRACKING_CONFIDENCE,
        }),
      )
      .then((landmarker) => {
        if (cancelled) {
          landmarker.close();
          return;
        }
        landmarkerRef.current = landmarker;
        setReady(true);
      })
      .catch((err) => setError(String(err)));

    return () => {
      cancelled = true;
      landmarkerRef.current?.close();
      landmarkerRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!active || !ready) return;
    const video = videoRef.current;
    const landmarker = landmarkerRef.current;
    const mirrorCanvas = mirrorCanvasRef.current;
    const ctx = mirrorCanvas.getContext('2d');
    if (!video || !landmarker || !ctx) return;

    let rafId: number;

    const detect = () => {
      rafId = requestAnimationFrame(detect);
      if (video.readyState < 2 || video.videoWidth === 0) return;

      const width = video.videoWidth;
      const height = video.videoHeight;
      if (mirrorCanvas.width !== width || mirrorCanvas.height !== height) {
        mirrorCanvas.width = width;
        mirrorCanvas.height = height;
      }

      ctx.save();
      ctx.scale(-1, 1);
      ctx.drawImage(video, -width, 0, width, height);
      ctx.restore();

      const now = performance.now();
      const detection = landmarker.detectForVideo(mirrorCanvas, now);
      const timestampSeconds = now / 1000;

      const detectedHands = new Set<'Left' | 'Right'>();
      const preprocessedByHand: Partial<Record<'Left' | 'Right', number[]>> = {};
      const frameResults: HandGestureResult[] = detection.landmarks.map((landmarks, i) => {
        const taskVisionLabel = (detection.handedness[i]?.[0]?.categoryName ?? 'Right') as 'Left' | 'Right';
        const handednessLabel = correctHandedness(taskVisionLabel);
        detectedHands.add(handednessLabel);
        // 겹침으로 인한 좌표 떨림을 완화하는 1€ 필터 (app.py의 landmark_smoother.smooth 포팅).
        const rawLandmarkList = calcLandmarkList(landmarks, width, height);
        const landmarkList = smootherRef.current.smooth(handednessLabel, rawLandmarkList, timestampSeconds);
        preprocessedByHand[handednessLabel] = preProcessLandmark(landmarkList);
        return { handedness: handednessLabel, landmarks };
      });
      setResults(frameResults);

      // 화면에서 사라진 손의 필터 상태를 버려서, 다시 잡혔을 때 사라지기 직전 위치로
      // 스냅되며 튀지 않게 한다 (app.py의 for hand_label not in detected_hands: reset()).
      (['Left', 'Right'] as const).forEach((label) => {
        if (!detectedHands.has(label)) smootherRef.current.reset(label);
      });

      if (preprocessedByHand.Left && preprocessedByHand.Right) {
        const combined = combineTwoHandLandmarks(preprocessedByHand.Left, preprocessedByHand.Right);
        const { label, confidence } = classifyKeyPoint(combined);

        const window = stabilizeWindowRef.current;
        window.push(label);
        if (window.length > STABILIZE_WINDOW) window.shift();
        const stableLabel = mostCommon(window);
        setCombo({ label: stableLabel, confidence });
      } else {
        stabilizeWindowRef.current = [];
        setCombo({ label: null, confidence: 0 });
      }
    };

    rafId = requestAnimationFrame(detect);
    return () => cancelAnimationFrame(rafId);
  }, [active, ready, videoRef]);

  // active가 꺼지면 마지막 판정을 비운다. 안 그러면 루프만 멈추고 직전 콤보 값이 그대로 남아,
  // 내 화면과 다른 참가자 보드에 아직 인식되는 것처럼 보인다(탈락 직후가 특히 그렇다).
  useEffect(() => {
    if (active) return;
    stabilizeWindowRef.current = [];
    setResults([]);
    setCombo({ label: null, confidence: 0 });
  }, [active]);

  return { results, combo, ready, error, mirrorCanvasRef };
}
