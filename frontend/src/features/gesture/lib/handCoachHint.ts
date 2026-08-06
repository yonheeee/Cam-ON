// 손이 왜 안 잡히는지 플레이어에게 한 줄로 알려주는 안내 문구 판단.
//
// 인식 실패의 원인은 실제 플레이테스트에서 거의 두 가지로 갈렸다.
//   1. 카메라에서 너무 멀다 — 손은 검출되는데 깊이 하한(handSelection.ts의 MIN_HAND_DEPTH)에
//      걸려 후보에서 버려진다. 화면엔 손이 멀쩡히 보이는데 스켈레톤이 안 붙으니 원인을
//      스스로 알아낼 방법이 없다.
//   2. 한 손이 프레임 밖이다 — 9개 라벨이 전부 양손 조합 포즈라 한 손만 잡히면 판정 자체가 없다.
// 둘은 해야 할 행동이 다르므로(다가오기 vs 손 넣기) 문구를 나눠 띄운다.
//
// 순서: 멀다가 먼저다. 멀어서 버려진 손이 있으면 "손이 하나만 잡힌" 것도 대개 같은 원인이라,
// 가까이 오는 것만으로 둘 다 풀린다.

export type HandCoachIssue = 'too-far' | 'hands-missing';

/** useHandGestureRecognition의 HandDepthInfo가 그대로 들어맞는 구조 — lib가 훅에 의존하지 않게 따로 둔다. */
export interface HandCoachDepth {
  nearest: number | null;
  min: number;
  farHandCount: number;
}

// 줄바꿈은 CSS(word-break: keep-all)가 띄어쓰기에서만 끊게 해두었으므로, 띄어쓰기 자리가 곧
// 줄이 갈릴 수 있는 자리다. 보조용언 "와 주세요"/"해 주세요"를 붙여 쓴 건 그래서다 — 띄우면
// "…가까이 와" / "주세요"로 갈려서 문장이 토막 난다(붙여쓰기도 맞춤법에 맞는 표기다).
export const HAND_COACH_MESSAGE: Record<HandCoachIssue, string> = {
  'too-far': '카메라에 조금만 더 가까이 와주세요',
  'hands-missing': '두 손이 모두 화면에 보이게 해주세요',
};

/**
 * 이번 프레임의 인식 상태에서 안내가 필요한 원인을 고른다. 안내할 게 없으면 null.
 *
 * 프레임 단위 판단이라 그대로 화면에 옮기면 깜빡인다 — 표시 타이밍은 useHandCoachHint가 맡는다.
 */
export function evaluateHandCoachIssue(handCount: number, depth: HandCoachDepth): HandCoachIssue | null {
  // 양손이 다 잡혔으면 판정이 도는 중이다 — 모양이 틀린 건 안내할 일이 아니다(그건 플레이다).
  if (handCount >= 2) return null;
  // 하한에 걸려 버린 손이 있다 = 손은 보이는데 멀다.
  if (depth.farHandCount > 0) return 'too-far';
  // 이력(hysteresis) 덕에 하한 아래인 채로 살아남은 손도 곧 놓친다 — 미리 당겨서 알려준다.
  if (depth.nearest !== null && depth.nearest < depth.min) return 'too-far';
  return 'hands-missing';
}
