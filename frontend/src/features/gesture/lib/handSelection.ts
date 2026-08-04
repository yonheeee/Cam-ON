import type { Point } from './landmarkPreprocessing';

// 왜 이 파일이 필요한가
// ---------------------
// MediaPipe HandLandmarker는 "가까운 손"이나 "화면 중앙의 손"을 골라주지 않는다. numHands 상한까지
// 자기 detection 점수 순으로 내놓을 뿐이라, 뒤쪽을 지나가는 사람의 손이나 손처럼 생긴 물체가
// 움직이면 그쪽이 상위에 올라오면서 정작 플레이어의 손 하나가 밀려난다. 양손 조합 포즈만 쓰는
// 닌자에서는 손 하나만 밀려도 판정이 통째로 죽는다.
//
// 그래서 numHands를 넉넉히 잡아 후보를 여러 개 받고(handLandmarker.ts), 그중에서 판정에 쓸
// 왼손/오른손 한 쌍을 여기서 직접 고른다. 기준은 두 가지다.
//   1. 카메라와의 거리 하한 — 너무 먼(=작게 보이는) 손은 후보에서 아예 버린다.
//   2. 가까운 손 + 가운데 손 우선 — 남은 후보 중 가장 크고 중앙에 가까운 손을 주 손으로 삼고,
//      반대쪽 손은 그 주 손 근처에 있는 것을 짝으로 고른다.
//
// "z축"에 대해: MediaPipe의 landmark.z는 카메라와의 거리가 아니다 — 같은 손의 손목을 원점으로 한
// 손 안에서의 상대 깊이라서, 손을 멀리 가져가도 값이 그만큼 커지지 않는다(worldLandmarks도 손
// 중심이 원점이라 마찬가지다). 카메라 거리를 알려주는 건 결국 **손이 화면에서 얼마나 크게
// 보이는가**뿐이다(겉보기 크기 ∝ 1/거리). 그래서 깊이 하한은 z값이 아니라 "프레임 높이 대비
// 손바닥 길이" 하한으로 잰다. 아래 depth가 그 값이다.

/** 손목(0)에서 각 손가락 MCP까지 — 손바닥 뼈대라 손가락을 접어도(주먹) 길이가 안 변한다. */
const PALM_SPAN_INDICES = [5, 9, 17] as const;

/**
 * 판정에 쓸 손의 깊이 하한 = 프레임 높이 대비 손바닥 길이. 이보다 작게(=멀리) 보이는 손은
 * 후보에서 버린다.
 *
 * 왜 이 값인가: 손목~MCP는 성인 손에서 대략 9cm다. 세로 화각 40°짜리 웹캠이면 60cm 거리에서
 * 보이는 세로 범위가 약 43cm라 비율이 0.21, 1.2m에서 0.10, 2m 넘어가면 0.06 아래로 떨어진다.
 * 0.09는 "팔을 뻗거나 몸을 젖힌 플레이어(~1.3m)까지는 통과, 뒤쪽을 지나가는 사람(2m+)은 차단"에
 * 해당하는 선이다. 다만 화각이 넓은 노트북 캠에서는 같은 거리에서도 비율이 작아지므로 환경에
 * 따라 조정이 필요하다.
 *
 * 다시 튜닝할 때: 대기방 → 환경설정 → 카메라 미리보기 하단 readout에 이번 프레임에서 가장
 * 가까운 손의 depth 실측값과 이 하한이 같이 찍힌다(dev 빌드, 또는 ?gestureDebug). 정상 거리인데
 * 손이 안 잡히면 그 숫자를 보고 내리고, 뒤쪽 사람 손이 계속 끼어들면 올린다.
 * handProximity.ts의 손 사이 거리 한계와 같은 방식으로 실측해서 정하는 값이다.
 */
export const MIN_HAND_DEPTH = 0.09;

/**
 * 이미 잡고 있던 손은 하한의 이 비율까지는 계속 인정한다. 하한에서 딱 끊으면 경계 근처에
 * 손을 둔 플레이어에게 스켈레톤이 붙었다 떨어졌다 하며 깜빡인다 — 한 번 잡으면 조금 멀어져도
 * 유지하고, 완전히 놓친 뒤 다시 잡을 때는 원래 하한을 넘게 한다.
 */
const DEPTH_RELEASE_RATIO = 0.85;

/**
 * 중앙에서 벗어난 만큼 점수를 깎는 비율. 곱셈으로 깎아서 "큰 손 우선"이 기본이고 중앙 여부는
 * 크기가 비슷할 때 갈리는 기준이 되게 한다 — 0.5면 프레임 가장자리(offset 1.0)의 손은 점수가
 * 절반이 되므로, 중앙의 손보다 2배 이상 크게 보여야 이긴다.
 */
const CENTER_PENALTY = 0.5;

/**
 * 짝 손을 고를 때 주 손에서 떨어진 거리에 매기는 감쇠 기준(손바닥 길이 단위). 조합 포즈는 두
 * 손이 붙어 있어야 하므로(handProximity.ts) 주 손 근처의 손이 짝일 가능성이 높다. 가장 많이
 * 벌어지는 cat의 실측 거리가 손바닥 4개분이라 그 값을 기준점으로 삼았다 — 이만큼 떨어지면
 * 점수가 절반이 된다.
 */
const PARTNER_GAP_TOLERANCE = 4;

/**
 * 선별 후보. 랜드마크 원본(L)은 이 모듈이 들여다보지 않고 고른 손과 함께 그대로 돌려주기만
 * 하므로 타입 인자로 받는다 — 호출부가 MediaPipe 타입을 그대로(스켈레톤 그리기에 필요한
 * z/visibility까지) 유지할 수 있고, 이 파일은 MediaPipe에 의존하지 않는다.
 */
export interface HandCandidate<L> {
  handedness: 'Left' | 'Right';
  /** MediaPipe 원본 정규화 좌표 — 스켈레톤 그리기에 그대로 넘긴다 */
  landmarks: L;
  /** 픽셀 좌표 (스무딩 전). 거리/크기 계산은 축 배율이 같은 픽셀 공간에서 해야 왜곡이 없다 */
  pixels: Point[];
  /** 손바닥 길이 (픽셀) */
  palmSpan: number;
  /** 손 중심 (픽셀) */
  center: Point;
  /** 프레임 높이 대비 손바닥 길이 — 카메라와의 거리 대용 지표 (클수록 가까움) */
  depth: number;
  /** 프레임 중심에서 손 중심까지 거리 (프레임 높이 단위, 0 = 정중앙) */
  centerOffset: number;
  /** 가까움 × 가운데 우선 점수 */
  score: number;
}

export interface HandSelection<L> {
  /** 판정에 쓸 손 — 최대 2개(왼손/오른손 각 1개) */
  hands: HandCandidate<L>[];
  /** 이번 프레임에서 가장 가까운(=크게 보이는) 손의 depth. 손이 하나도 없으면 null */
  nearestDepth: number | null;
  /** 깊이 하한에 걸려 버린 손 개수 — "먼 손을 무시했다"를 알려주는 데 쓴다 */
  farHandCount: number;
}

function distance(a: Point, b: Point): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1]);
}

function palmScale(pixels: Point[]): number {
  const wrist = pixels[0];
  const sum = PALM_SPAN_INDICES.reduce((acc, i) => acc + distance(wrist, pixels[i]), 0);
  return sum / PALM_SPAN_INDICES.length;
}

function centroid(pixels: Point[]): Point {
  const sum = pixels.reduce<Point>((acc, [x, y]) => [acc[0] + x, acc[1] + y], [0, 0]);
  return [sum[0] / pixels.length, sum[1] / pixels.length];
}

/**
 * 후보 하나의 크기/위치 지표를 계산한다. 손바닥 크기를 못 구할 만큼 좌표가 뭉개진 손은 null —
 * 깊이를 판단할 근거가 없는 손이라 후보에서 제외한다.
 *
 * 프레임 폭/높이를 모두 쓰지만 정규화는 높이로만 한다(x·y 모두). 픽셀 공간은 두 축의 배율이
 * 같으므로 한쪽 축으로만 나눠야 거리 비교가 왜곡되지 않는다.
 */
export function describeHandCandidate<L>(
  handedness: 'Left' | 'Right',
  landmarks: L,
  pixels: Point[],
  frameWidth: number,
  frameHeight: number,
): HandCandidate<L> | null {
  const palmSpan = palmScale(pixels);
  if (!Number.isFinite(palmSpan) || palmSpan <= 0 || frameHeight <= 0) return null;

  const center = centroid(pixels);
  const depth = palmSpan / frameHeight;
  const centerOffset = distance(center, [frameWidth / 2, frameHeight / 2]) / frameHeight;
  const score = depth * (1 - CENTER_PENALTY * Math.min(centerOffset, 1));

  return { handedness, landmarks, pixels, palmSpan, center, depth, centerOffset, score };
}

function partnerScore<L>(primary: HandCandidate<L>, candidate: HandCandidate<L>): number {
  const gapInPalms = distance(primary.center, candidate.center) / primary.palmSpan;
  return candidate.score / (1 + gapInPalms / PARTNER_GAP_TOLERANCE);
}

/**
 * 후보들 중 판정에 쓸 왼손/오른손 한 쌍을 고른다.
 *
 * @param engaged 직전 프레임에서 손을 잡고 있었는가 — 깊이 하한에 이력(hysteresis)을 주는 데 쓴다.
 */
export function selectComboHands<L>(candidates: HandCandidate<L>[], engaged: boolean): HandSelection<L> {
  const nearestDepth = candidates.reduce<number | null>(
    (max, c) => (max === null || c.depth > max ? c.depth : max),
    null,
  );

  const threshold = engaged ? MIN_HAND_DEPTH * DEPTH_RELEASE_RATIO : MIN_HAND_DEPTH;
  const near = candidates.filter((c) => c.depth >= threshold);
  const farHandCount = candidates.length - near.length;
  if (near.length === 0) return { hands: [], nearestDepth, farHandCount };

  // 주 손: 가장 크고 중앙에 가까운 손.
  const [primary, ...rest] = [...near].sort((a, b) => b.score - a.score);

  // 짝 손: 반대쪽 손 중에서 (크기·중앙 점수 ÷ 주 손과의 거리) 가 가장 높은 것. 같은 쪽 손이
  // 두 개 잡히는 오인식도 있으므로 handedness가 다른 후보만 본다 — 84차원 조합 입력은 왼손
  // 슬롯과 오른손 슬롯에 각각 하나씩 들어가야 학습 때와 같은 매핑이 된다.
  let partner: HandCandidate<L> | null = null;
  let bestPartnerScore = -Infinity;
  for (const candidate of rest) {
    if (candidate.handedness === primary.handedness) continue;
    const score = partnerScore(primary, candidate);
    if (score > bestPartnerScore) {
      bestPartnerScore = score;
      partner = candidate;
    }
  }

  return {
    hands: partner ? [primary, partner] : [primary],
    nearestDepth,
    farHandCount,
  };
}
