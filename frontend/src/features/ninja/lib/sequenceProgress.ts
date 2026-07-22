import { useEffect, useRef, useState } from 'react';

// hand-gesture-recognition-mediapipe/app.py의 HOLD_DURATION 개념을 포팅하되, 유지 시간은
// 0.7s·신뢰도 80% 이상 조건으로 조정했다. 원래 있던 SEQUENCE_STEP_TIMEOUT(단계 사이 제한시간,
// 넘기면 처음부터 다시)은 초보자가 다음 동작을 준비하는 동안 진행도가 갑자기 리셋돼버리는 게
// 너무 가혹해서 없앴다 — 라운드 자체 제한시간(서버가 관리) 안에만 다 끝내면 되고, 단계 사이
// 텀은 얼마나 걸리든 상관없다. 콤보 진행 추적은 백엔드가 아니라 여기(AI/프론트 계층)에서 한다 —
// 서버는 완성된 순간의 skillId 하나만 받는다(설계 세션에서 합의: 어느 쪽이 추적하든 보안은
// 동일하고, AI/프론트 추적이 구현이 더 단순함).
const HOLD_DURATION_MS = 700;
const CONFIDENCE_THRESHOLD = 0.8;
const CHECK_INTERVAL_MS = 100;

export interface SequenceProgress {
  stepIndex: number;
  completed: boolean;
  /** 현재 단계 유지 진행률 0~1. 기대한 포즈를 신뢰도 조건 이상으로 잡고 있지 않으면 0. */
  holdProgress: number;
  reset: () => void;
}

export function useSequenceProgress(
  requiredSequence: string[] | null,
  comboLabel: string | null,
  comboConfidence: number,
): SequenceProgress {
  const [stepIndex, setStepIndex] = useState(0);
  const [completed, setCompleted] = useState(false);
  const [holdProgress, setHoldProgress] = useState(0);

  const comboLabelRef = useRef(comboLabel);
  const comboConfidenceRef = useRef(comboConfidence);
  const stepIndexRef = useRef(0);
  const holdLabelRef = useRef<string | null>(null);
  const holdStartedAtRef = useRef(0);

  useEffect(() => {
    comboLabelRef.current = comboLabel;
    comboConfidenceRef.current = comboConfidence;
  }, [comboLabel, comboConfidence]);

  const reset = () => {
    stepIndexRef.current = 0;
    holdLabelRef.current = null;
    setStepIndex(0);
    setCompleted(false);
    setHoldProgress(0);
  };

  // 라운드(요구 시퀀스)가 바뀌면 진행 상태 초기화. reset()을 의존성에 안 넣으려고 인라인으로 뒀다.
  useEffect(() => {
    stepIndexRef.current = 0;
    holdLabelRef.current = null;
    setStepIndex(0);
    setCompleted(false);
    setHoldProgress(0);
  }, [requiredSequence]);

  useEffect(() => {
    if (!requiredSequence || requiredSequence.length === 0 || completed) return;

    const interval = setInterval(() => {
      const now = Date.now();
      const expected = requiredSequence[stepIndexRef.current];
      const label = comboLabelRef.current;
      const confidence = comboConfidenceRef.current;
      const matches = label === expected && confidence >= CONFIDENCE_THRESHOLD;

      if (!matches) {
        holdLabelRef.current = null;
        setHoldProgress(0);
        return;
      }

      if (holdLabelRef.current !== expected) {
        holdLabelRef.current = expected;
        holdStartedAtRef.current = now;
      }

      const elapsed = now - holdStartedAtRef.current;
      setHoldProgress(Math.min(elapsed / HOLD_DURATION_MS, 1));

      if (elapsed >= HOLD_DURATION_MS) {
        holdLabelRef.current = null;
        setHoldProgress(0);
        const nextIndex = stepIndexRef.current + 1;
        if (nextIndex >= requiredSequence.length) {
          setCompleted(true);
        } else {
          stepIndexRef.current = nextIndex;
          setStepIndex(nextIndex);
        }
      }
    }, CHECK_INTERVAL_MS);

    return () => clearInterval(interval);
  }, [requiredSequence, completed]);

  return { stepIndex, completed, holdProgress, reset };
}
