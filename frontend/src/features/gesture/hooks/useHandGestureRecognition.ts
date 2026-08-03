import { useEffect, useRef, useState, type RefObject } from 'react';
import { HandLandmarker, type NormalizedLandmark } from '@mediapipe/tasks-vision';
import {
  calcLandmarkList,
  preProcessLandmark,
  combineTwoHandLandmarks,
  type Point,
} from '../lib/landmarkPreprocessing';
import { createHandLandmarker } from '../lib/handLandmarker';
import { classifyKeyPoint } from '../lib/keypointClassifier';
import { HandLandmarkSmoother } from '../lib/oneEuroFilter';
import { measureHandProximity, handProximityFactor, handGapLimitFor } from '../lib/handProximity';

// app.py의 combo_sign_history = deque(maxlen=5) — 프레임 하나짜리 오인식(손 떨림 등)에
// 흔들리지 않도록 최근 5프레임의 다수결로 안정화한다.
const STABILIZE_WINDOW = 5;

export interface HandGestureResult {
  handedness: 'Left' | 'Right';
  landmarks: NormalizedLandmark[];
}

export interface ComboGestureResult {
  /** 모양 + 손 사이 거리 조건을 모두 통과한 최종 판정. 양손이 다 잡혀야만 채워진다 (한 손이면 null) */
  label: string | null;
  /** 거리 감쇠가 반영된 신뢰도 (sequenceProgress의 0.8 임계값이 이 값을 본다) */
  confidence: number;
  /** 거리 조건을 적용하기 전 분류기 원본 라벨 — "모양은 맞는데 손이 멀다"를 구분해 안내하려고 남긴다 */
  rawLabel: string | null;
  /** 두 손 사이 최소 거리 (손바닥 길이 단위). 양손이 안 잡히거나 스케일을 못 구하면 null */
  handGap: number | null;
  /** rawLabel에 허용되는 최대 거리 (같은 단위) */
  handGapLimit: number | null;
}

const EMPTY_COMBO: ComboGestureResult = {
  label: null,
  confidence: 0,
  rawLabel: null,
  handGap: null,
  handGapLimit: null,
};

// 거리 조건에 걸려 버려진 프레임도 다수결에 참여해야 한다(그냥 건너뛰면 직전 판정이 그대로 남아
// 손을 벌려도 계속 인식된 것처럼 보인다). 그래서 창에는 null도 그대로 넣는다.
function mostCommon(values: (string | null)[]): string | null {
  const counts = new Map<string | null, number>();
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
  const [combo, setCombo] = useState<ComboGestureResult>(EMPTY_COMBO);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const landmarkerRef = useRef<HandLandmarker | null>(null);
  const mirrorCanvasRef = useRef<HTMLCanvasElement>(document.createElement('canvas'));
  const smootherRef = useRef(new HandLandmarkSmoother(1.0, 0.3, 1.0));
  const stabilizeWindowRef = useRef<(string | null)[]>([]);

  useEffect(() => {
    let cancelled = false;
    createHandLandmarker()
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
      // 손 사이 거리 판정용 원본(픽셀) 좌표. 분류기 입력(preprocessedByHand)은 손마다 따로
      // 정규화돼서 두 손의 상대 위치가 지워지므로, 거리는 정규화 전 좌표로 재야 한다.
      const pixelByHand: Partial<Record<'Left' | 'Right', Point[]>> = {};
      const frameResults: HandGestureResult[] = detection.landmarks.map((landmarks, i) => {
        const taskVisionLabel = (detection.handedness[i]?.[0]?.categoryName ?? 'Right') as 'Left' | 'Right';
        const handednessLabel = correctHandedness(taskVisionLabel);
        detectedHands.add(handednessLabel);
        // 겹침으로 인한 좌표 떨림을 완화하는 1€ 필터 (app.py의 landmark_smoother.smooth 포팅).
        const rawLandmarkList = calcLandmarkList(landmarks, width, height);
        const landmarkList = smootherRef.current.smooth(handednessLabel, rawLandmarkList, timestampSeconds);
        pixelByHand[handednessLabel] = landmarkList;
        preprocessedByHand[handednessLabel] = preProcessLandmark(landmarkList);
        return { handedness: handednessLabel, landmarks };
      });
      setResults(frameResults);

      // 화면에서 사라진 손의 필터 상태를 버려서, 다시 잡혔을 때 사라지기 직전 위치로
      // 스냅되며 튀지 않게 한다 (app.py의 for hand_label not in detected_hands: reset()).
      (['Left', 'Right'] as const).forEach((label) => {
        if (!detectedHands.has(label)) smootherRef.current.reset(label);
      });

      if (preprocessedByHand.Left && preprocessedByHand.Right && pixelByHand.Left && pixelByHand.Right) {
        const combined = combineTwoHandLandmarks(preprocessedByHand.Left, preprocessedByHand.Right);
        const { label, confidence } = classifyKeyPoint(combined);

        // 모양 판정에 손 사이 거리 조건을 얹는다 — 9개 라벨이 전부 양손 조합 포즈라서 모든
        // 판정에 공통으로 적용한다(자세한 이유는 lib/handProximity.ts 주석).
        // 스케일을 못 구한 프레임(proximity=null)은 거리 조건 없이 모양 판정만 쓴다.
        const proximity = measureHandProximity(pixelByHand.Left, pixelByHand.Right);
        const factor = proximity ? handProximityFactor(label, proximity) : 1;
        const gatedLabel = factor > 0 ? label : null;

        const window = stabilizeWindowRef.current;
        window.push(gatedLabel);
        if (window.length > STABILIZE_WINDOW) window.shift();
        const stableLabel = mostCommon(window);
        setCombo({
          label: stableLabel,
          confidence: stableLabel ? confidence * factor : 0,
          rawLabel: label,
          handGap: proximity?.gap ?? null,
          handGapLimit: handGapLimitFor(label),
        });
      } else {
        stabilizeWindowRef.current = [];
        setCombo(EMPTY_COMBO);
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
    setCombo(EMPTY_COMBO);
  }, [active]);

  return { results, combo, ready, error, mirrorCanvasRef };
}
