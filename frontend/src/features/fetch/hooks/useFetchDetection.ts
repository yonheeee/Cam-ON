import { useEffect, useRef, useState } from 'react';
import { aiApi, type DetectionResult } from '../api/aiApi';

// 비디오 엘리먼트에서 중앙 ROI를 잘라 AI 서버로 주기 전송하고,
// 제시어 기준 판정(isTargetMatch)이 연속 N프레임 유지되면 성공을 알리는 훅.
// (ai/app/static/test.html의 검증된 파라미터/로직을 React로 이식)
const INTERVAL_MS = 200;
const ROI_RATIO = 0.8; // 화면 짧은 변의 80%
const CROP_SIZE = 384; // so400m 입력 해상도
const REQUIRED_STREAK = 2; // 연속 2회(0.4초) 일치 시 성공

interface UseFetchDetectionOptions {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  target: string | null;
  active: boolean;
  onSuccess: (elapsedMs: number) => void;
}

export function useFetchDetection({ videoRef, target, active, onSuccess }: UseFetchDetectionOptions) {
  const [lastResult, setLastResult] = useState<DetectionResult | null>(null);
  const [streak, setStreak] = useState(0);
  const [error, setError] = useState<string | null>(null);
  // 진단용 — 최근 요청의 왕복 시간 (crop+인코딩+네트워크+추론 전부 포함)
  const [latencyMs, setLatencyMs] = useState<number | null>(null);

  // 타이머 콜백에서 stale closure를 피하기 위한 ref들
  const inflightRef = useRef(false);
  const streakRef = useRef(0);
  const doneRef = useRef(false);
  const startedAtRef = useRef(0);
  const onSuccessRef = useRef(onSuccess);
  onSuccessRef.current = onSuccess;

  useEffect(() => {
    if (!active || !target) return;

    // 라운드(target) 단위로 초기화
    doneRef.current = false;
    streakRef.current = 0;
    startedAtRef.current = Date.now();
    setStreak(0);
    setLastResult(null);
    setError(null);

    const tick = async () => {
      const video = videoRef.current;
      if (inflightRef.current || doneRef.current || !video || !video.videoWidth) return;
      inflightRef.current = true;
      const requestStart = performance.now();
      try {
        const blob = await cropRoi(video);
        if (!blob) return;
        const result = await aiApi.detect(blob, target);
        setLatencyMs(Math.round(performance.now() - requestStart));
        if (doneRef.current) return;
        setLastResult(result);
        setError(null);

        if (result.isTargetMatch) {
          streakRef.current += 1;
          setStreak(streakRef.current);
          if (streakRef.current >= REQUIRED_STREAK) {
            doneRef.current = true;
            onSuccessRef.current(Date.now() - startedAtRef.current);
          }
        } else {
          streakRef.current = 0;
          setStreak(0);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'AI 서버 연결 실패');
      } finally {
        inflightRef.current = false;
      }
    };

    const timer = setInterval(() => void tick(), INTERVAL_MS);
    return () => clearInterval(timer);
  }, [active, target, videoRef]);

  return { lastResult, streak, requiredStreak: REQUIRED_STREAK, error, latencyMs };
}

/** 비디오 중앙 정사각 ROI(80%)를 384px JPEG로 crop */
function cropRoi(video: HTMLVideoElement): Promise<Blob | null> {
  const side = Math.min(video.videoWidth, video.videoHeight) * ROI_RATIO;
  const sx = (video.videoWidth - side) / 2;
  const sy = (video.videoHeight - side) / 2;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = CROP_SIZE;
  const ctx = canvas.getContext('2d');
  if (!ctx) return Promise.resolve(null);
  ctx.drawImage(video, sx, sy, side, side, 0, 0, CROP_SIZE, CROP_SIZE);
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
}
