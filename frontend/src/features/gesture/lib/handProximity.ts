import type { Point } from './landmarkPreprocessing';

// 왜 이 파일이 필요한가
// ---------------------
// 분류기 입력(84차원)은 preProcessLandmark가 손마다 "자기 손목 기준 상대좌표 → 자기 손 안의
// 최댓값으로 정규화"한 결과를 이어붙인 것이다. 즉 각 손의 **모양**만 담겨 있고 두 손이 서로
// 얼마나 떨어져 있는지는 입력에서 통째로 사라진다. 그래서 예를 들어 mouse(왼손 엄지척을 오른손이
// 감싸는, 두 손이 붙어야 하는 포즈)는 두 손을 화면 양끝에 벌려 놓고 각자 모양만 맞춰도 100%로
// 통과했다(2026-08-03 확인).
//
// 학습 CSV(model/keypoint_classifier/keypoint.csv)에도 정규화가 끝난 값만 남아 있어서 손 사이
// 거리를 특징으로 추가해 재학습하는 건 데이터를 다시 모으는 것과 같다. 그래서 모델은 그대로 두고,
// 분류 결과에 **기하학적 거리 조건**을 후처리로 얹는다 — 9개 라벨이 전부 양손 조합 포즈이므로
// 거리 조건은 특정 포즈만의 예외가 아니라 모든 판정에 공통으로 적용한다.

/** 손목(0)에서 각 손가락 MCP까지 — 손바닥 뼈대라 손가락을 접어도(주먹) 길이가 안 변한다. */
const PALM_SPAN_INDICES = [5, 9, 17] as const;

/**
 * 라벨별 허용 최대 거리(단위: 손바닥 길이). 카메라와의 거리에 따라 픽셀 크기가 달라지므로
 * 절대 픽셀이 아니라 "손바닥 몇 개분"으로 환산한 값을 쓴다.
 *
 * 두 손이 실제로 맞닿아야 하는 포즈(엄지를 감싸기, 손가락 걸기, 손가락끼리 대기)는 정상 수행 시
 * 최소 거리가 0에 가깝다 — 0.9는 촬영 각도나 랜드마크 흔들림을 감안한 여유값이다. 손 사이가
 * 의도적으로 벌어지는 포즈(cat: 두 주먹 나란히, sailor_moon: 한 손을 반대쪽 팔에 얹기)는 더 넉넉히,
 * 아직 실측 기준이 없는 포즈(girl_V — 손모양 이미지조차 없다)는 DEFAULT를 쓴다.
 *
 * 튜닝: GesturePanel(패널 모드)이 실측 거리와 이 한계를 같이 표시한다. 포즈를 제대로 잡았는데도
 * 판정이 안 되면 그 숫자를 보고 해당 라벨 값을 올리면 된다.
 */
const MAX_GAP_BY_LABEL: Record<string, number> = {
  mouse: 0.9,
  Horse: 0.9,
  rabbit: 0.9,
  cow: 0.9,
  spider: 0.9,
  snake: 1.3,
  cat: 2.0,
  sailor_moon: 2.0,
};
const DEFAULT_MAX_GAP = 2.5;

// 최소 거리만 보면 "두 손을 멀찍이 두고 손가락만 서로 뻗어서 끝을 스치게" 하는 식으로는 여전히
// 통과할 수 있다. 손 중심 사이 거리에도 한계를 두어 그런 경우를 걸러낸다 — 손이 맞닿아 있어도
// 중심끼리는 손바닥 2개분 정도 떨어질 수 있으니 그만큼을 최소 거리 한계에 더해서 쓴다.
const CENTER_GAP_ALLOWANCE = 2.2;

// 한계선에서 통과/차단이 딱 끊기면 경계 근처에서 판정이 깜빡인다. 한계 ~ 한계+SOFT_MARGIN
// 구간에서는 신뢰도를 선형으로 깎아, "조금 멀다"가 곧 "홀드가 안 채워진다"로 이어지게 한다
// (sequenceProgress의 CONFIDENCE_THRESHOLD=0.8이 실질 차단선 역할을 한다).
const SOFT_MARGIN = 0.4;

export interface HandProximity {
  /** 두 손의 가장 가까운 랜드마크 사이 거리 (손바닥 길이 단위) */
  gap: number;
  /** 두 손 중심 사이 거리 (손바닥 길이 단위) */
  centerGap: number;
}

function distance(a: Point, b: Point): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1]);
}

function palmScale(landmarkList: Point[]): number {
  const wrist = landmarkList[0];
  const sum = PALM_SPAN_INDICES.reduce((acc, i) => acc + distance(wrist, landmarkList[i]), 0);
  return sum / PALM_SPAN_INDICES.length;
}

function centroid(landmarkList: Point[]): Point {
  const sum = landmarkList.reduce<Point>((acc, [x, y]) => [acc[0] + x, acc[1] + y], [0, 0]);
  return [sum[0] / landmarkList.length, sum[1] / landmarkList.length];
}

/**
 * 두 손의 거리를 손바닥 길이 단위로 잰다. 입력은 calcLandmarkList가 만든 픽셀 좌표
 * (정규화 좌표를 쓰면 프레임이 정사각형이 아닐 때 x/y 축 배율이 달라 거리가 왜곡된다).
 *
 * 손바닥 크기를 못 구할 만큼 랜드마크가 뭉개진 프레임에서는 null — 호출부는 이 경우 거리 조건을
 * 적용하지 않는다(잘못된 스케일로 정상 포즈를 떨어뜨리는 것보다 낫다).
 */
export function measureHandProximity(left: Point[], right: Point[]): HandProximity | null {
  const scale = (palmScale(left) + palmScale(right)) / 2;
  if (!Number.isFinite(scale) || scale <= 0) return null;

  let minDistance = Infinity;
  for (const l of left) {
    for (const r of right) {
      const d = distance(l, r);
      if (d < minDistance) minDistance = d;
    }
  }

  return {
    gap: minDistance / scale,
    centerGap: distance(centroid(left), centroid(right)) / scale,
  };
}

/** 이 라벨이 허용하는 두 손 사이 최대 거리 (손바닥 길이 단위). */
export function handGapLimitFor(label: string): number {
  return MAX_GAP_BY_LABEL[label] ?? DEFAULT_MAX_GAP;
}

function ramp(value: number, limit: number): number {
  if (value <= limit) return 1;
  if (value >= limit + SOFT_MARGIN) return 0;
  return (limit + SOFT_MARGIN - value) / SOFT_MARGIN;
}

/**
 * 거리 조건을 반영한 신뢰도 배수 (1 = 조건 충족, 0 = 너무 멀어서 이 포즈로 인정 못 함).
 * 호출부는 이 값을 분류기 신뢰도에 곱하고, 0이면 라벨 자체를 버린다.
 */
export function handProximityFactor(label: string, proximity: HandProximity): number {
  const gapLimit = handGapLimitFor(label);
  return Math.min(ramp(proximity.gap, gapLimit), ramp(proximity.centerGap, gapLimit + CENTER_GAP_ALLOWANCE));
}
