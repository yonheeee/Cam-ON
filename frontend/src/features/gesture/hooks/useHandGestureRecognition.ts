import { useEffect, useRef, useState, type RefObject } from 'react';
import { FilesetResolver, HandLandmarker, type NormalizedLandmark } from '@mediapipe/tasks-vision';
import { calcLandmarkList, preProcessLandmark, preProcessPointHistory, type Point } from '../lib/landmarkPreprocessing';
import { classifyKeyPoint } from '../lib/keypointClassifier';
import { classifyPointHistory } from '../lib/pointHistoryClassifier';

const WASM_BASE = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm';
const MODEL_URL =
  'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task';
const HISTORY_LENGTH = 16;
const POINTER_LABEL_INDEX = 2; // KEYPOINT_LABELS[2] === 'Pointer'

export interface HandGestureResult {
  handedness: 'Left' | 'Right';
  landmarks: NormalizedLandmark[];
  handSignLabel: string;
  fingerGestureLabel: string;
}

interface HandState {
  pointHistory: Point[];
  fingerGestureHistory: string[];
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

// app.py 메인 루프(랜드마크 추출 -> 전처리 -> 손모양/손동작 분류 -> 히스토리 유지)를
// 브라우저에서 재생 중인 <video>에 대해 requestAnimationFrame으로 반복 실행하도록 포팅.
export function useHandGestureRecognition(videoRef: RefObject<HTMLVideoElement | null>, active: boolean) {
  const [results, setResults] = useState<HandGestureResult[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const landmarkerRef = useRef<HandLandmarker | null>(null);
  const handStateRef = useRef(new Map<string, HandState>());

  useEffect(() => {
    let cancelled = false;
    FilesetResolver.forVisionTasks(WASM_BASE)
      .then((vision) =>
        HandLandmarker.createFromOptions(vision, {
          baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
          runningMode: 'VIDEO',
          numHands: 2,
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
    if (!video || !landmarker) return;

    let rafId: number;

    const detect = () => {
      rafId = requestAnimationFrame(detect);
      if (video.readyState < 2 || video.videoWidth === 0) return;

      const detection = landmarker.detectForVideo(video, performance.now());
      const width = video.videoWidth;
      const height = video.videoHeight;

      const frameResults: HandGestureResult[] = detection.landmarks.map((landmarks, i) => {
        const handednessLabel = (detection.handedness[i]?.[0]?.categoryName ?? 'Right') as 'Left' | 'Right';
        const landmarkList = calcLandmarkList(landmarks, width, height);
        const handSign = classifyKeyPoint(preProcessLandmark(landmarkList));

        if (!handStateRef.current.has(handednessLabel)) {
          handStateRef.current.set(handednessLabel, { pointHistory: [], fingerGestureHistory: [] });
        }
        const state = handStateRef.current.get(handednessLabel)!;

        state.pointHistory.push(handSign.index === POINTER_LABEL_INDEX ? landmarkList[8] : [0, 0]);
        if (state.pointHistory.length > HISTORY_LENGTH) state.pointHistory.shift();

        let fingerGestureLabel = 'Stop';
        if (state.pointHistory.length === HISTORY_LENGTH) {
          const processed = preProcessPointHistory(state.pointHistory, width, height);
          const fingerGesture = classifyPointHistory(processed);
          state.fingerGestureHistory.push(fingerGesture.label);
          if (state.fingerGestureHistory.length > HISTORY_LENGTH) state.fingerGestureHistory.shift();
          fingerGestureLabel = mostCommon(state.fingerGestureHistory);
        }

        return {
          handedness: handednessLabel,
          landmarks,
          handSignLabel: handSign.label,
          fingerGestureLabel,
        };
      });

      setResults(frameResults);
    };

    rafId = requestAnimationFrame(detect);
    return () => cancelAnimationFrame(rafId);
  }, [active, ready, videoRef]);

  return { results, ready, error };
}
