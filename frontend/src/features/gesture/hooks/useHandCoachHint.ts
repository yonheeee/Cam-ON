import { useEffect, useRef, useState } from 'react';
import {
  evaluateHandCoachIssue,
  type HandCoachDepth,
  type HandCoachIssue,
} from '../lib/handCoachHint';

// 인식 루프는 초당 30~60번 도므로 원인 판단도 매 프레임 바뀐다. 그걸 그대로 그리면 손을
// 조금만 움직여도 문구가 깜빡이며 오히려 화면을 어지럽힌다. 그래서 "이 상태가 얼마나
// 이어졌는가"로 표시를 결정한다 — 잠깐 놓친 건 무시하고, 실제로 막혀 있을 때만 뜬다.

/** 같은 원인이 이만큼 계속 이어져야 문구를 띄운다. 손을 바꿔 쥐는 사이의 공백(~0.5초)보다 길게. */
const SHOW_AFTER_MS = 800;
/** 다시 잡히면 이만큼 뒤에 지운다. 바로 지우면 경계에서 문구가 명멸한다. */
const HIDE_AFTER_MS = 400;
/** 표시 판정 주기. 프레임마다 setState 하지 않으려고 별도 타이머로 돌린다. */
const TICK_MS = 150;

/**
 * 손 인식이 막혀 있을 때 띄울 안내 원인을 돌려준다(띄울 게 없으면 null).
 *
 * @param handCount 이번 프레임에 판정용으로 선별된 손 개수 (useHandGestureRecognition의 results.length)
 * @param depth     선별 결과의 깊이 정보 (같은 훅의 depth)
 * @param active    안내를 켤 구간인가. 인식 루프가 도는 동안이라도 플레이어가 손을 들 차례가
 *                  아니면(인터미션, 이펙트 재생, 모델 로딩 중) 꺼서 잔소리가 되지 않게 한다.
 */
export function useHandCoachHint(
  handCount: number,
  depth: HandCoachDepth,
  active: boolean,
): HandCoachIssue | null {
  const [hint, setHint] = useState<HandCoachIssue | null>(null);
  const issueRef = useRef<HandCoachIssue | null>(null);
  const sinceRef = useRef(performance.now());

  // 프레임마다 도는 쪽 — 상태가 바뀐 시각만 기록한다(리렌더 없음).
  useEffect(() => {
    const issue = active ? evaluateHandCoachIssue(handCount, depth) : null;
    if (issue === issueRef.current) return;
    issueRef.current = issue;
    sinceRef.current = performance.now();
  }, [active, handCount, depth]);

  useEffect(() => {
    if (!active) {
      issueRef.current = null;
      sinceRef.current = performance.now();
      setHint(null);
      return;
    }
    const timer = window.setInterval(() => {
      const issue = issueRef.current;
      const elapsed = performance.now() - sinceRef.current;
      setHint((prev) => {
        if (prev === issue) return prev;
        if (issue === null) return elapsed >= HIDE_AFTER_MS ? null : prev;
        // 원인이 바뀌는 경우(멀다 → 손 없음)도 같은 대기 시간을 쓴다 — 두 문구가 번갈아
        // 튀는 게 아무 문구도 없는 것보다 나쁘다.
        return elapsed >= SHOW_AFTER_MS ? issue : prev;
      });
    }, TICK_MS);
    return () => window.clearInterval(timer);
  }, [active]);

  return hint;
}
