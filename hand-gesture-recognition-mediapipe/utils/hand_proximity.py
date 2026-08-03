#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""양손 조합 포즈의 "두 손이 실제로 붙어 있는가"를 재는 후처리 모듈.

왜 필요한가: 분류기 입력(84차원)은 pre_process_landmark가 손마다 자기 손목 기준으로 상대화하고
자기 손 안의 최댓값으로 정규화한 값을 이어붙인 것이다. 각 손의 **모양**만 담기고 두 손의 상대
위치는 입력에서 사라지므로, mouse(왼손 엄지척을 오른손이 감싸는 포즈)처럼 맞닿아야 하는 포즈도
두 손을 화면 양끝에 벌려 놓고 모양만 맞추면 통과한다(2026-08-03 확인).

학습 CSV에도 정규화된 값만 남아 있어 거리 특징을 넣어 재학습하려면 데이터를 다시 모아야 한다.
그래서 모델은 그대로 두고 분류 결과에 기하학적 거리 조건을 얹는다. 프론트엔드 구현
(frontend/src/features/gesture/lib/handProximity.ts)과 같은 기준·같은 상수를 쓴다 — 한쪽을
바꾸면 다른 쪽도 맞춰야 판정이 어긋나지 않는다.
"""
import math

# 손목(0)에서 각 손가락 MCP까지 — 손바닥 뼈대라 손가락을 접어도(주먹) 길이가 안 변한다.
PALM_SPAN_INDICES = (5, 9, 17)

# 라벨별 허용 최대 거리(단위: 손바닥 길이). 카메라와의 거리에 따라 픽셀 크기가 달라지므로
# 절대 픽셀이 아니라 "손바닥 몇 개분"으로 환산해서 비교한다.
# 실제로 맞닿아야 하는 포즈는 정상 수행 시 최소 거리가 0에 가깝고(0.9는 각도·떨림 여유),
# 손 사이가 의도적으로 벌어지는 포즈(cat: 두 주먹 나란히, sailor_moon: 반대쪽 팔에 얹기)는 넉넉히,
# 아직 실측 기준이 없는 포즈(girl_V)는 DEFAULT를 쓴다.
MAX_GAP_BY_LABEL = {
    'mouse': 0.9,
    'Horse': 0.9,
    'rabbit': 0.9,
    'cow': 0.9,
    'spider': 0.9,
    'snake': 1.3,
    'cat': 2.0,
    'sailor_moon': 2.0,
}
DEFAULT_MAX_GAP = 2.5

# 최소 거리만 보면 두 손을 멀찍이 두고 손가락 끝만 스치게 해서 통과할 수 있다. 손 중심 사이
# 거리에도 한계를 둔다 — 맞닿은 포즈도 중심끼리는 손바닥 2개분쯤 떨어질 수 있어 그만큼 더한다.
CENTER_GAP_ALLOWANCE = 2.2

# 한계선에서 딱 끊기면 경계 근처에서 판정이 깜빡이므로 한계~한계+SOFT_MARGIN 구간은 선형 감쇠.
SOFT_MARGIN = 0.4


def _distance(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _palm_scale(landmark_list):
    wrist = landmark_list[0]
    spans = [_distance(wrist, landmark_list[i]) for i in PALM_SPAN_INDICES]
    return sum(spans) / len(spans)


def _centroid(landmark_list):
    xs = [p[0] for p in landmark_list]
    ys = [p[1] for p in landmark_list]
    return [sum(xs) / len(xs), sum(ys) / len(ys)]


def measure_hand_proximity(left, right):
    """두 손의 (최소 거리, 중심 거리)를 손바닥 길이 단위로 반환. 스케일을 못 구하면 None.

    입력은 calc_landmark_list가 만든 픽셀 좌표 — 정규화 좌표를 쓰면 프레임이 정사각형이 아닐 때
    x/y 배율이 달라 거리가 왜곡된다.
    """
    scale = (_palm_scale(left) + _palm_scale(right)) / 2
    if scale <= 0:
        return None

    min_distance = min(_distance(l, r) for l in left for r in right)
    center_gap = _distance(_centroid(left), _centroid(right))
    return min_distance / scale, center_gap / scale


def hand_gap_limit_for(label):
    """이 라벨이 허용하는 두 손 사이 최대 거리 (손바닥 길이 단위)."""
    return MAX_GAP_BY_LABEL.get(label, DEFAULT_MAX_GAP)


def _ramp(value, limit):
    if value <= limit:
        return 1.0
    if value >= limit + SOFT_MARGIN:
        return 0.0
    return (limit + SOFT_MARGIN - value) / SOFT_MARGIN


def hand_proximity_factor(label, proximity):
    """거리 조건 충족도 (1.0 = 충족, 0.0 = 너무 멀어서 이 포즈로 인정 못 함)."""
    gap, center_gap = proximity
    gap_limit = hand_gap_limit_for(label)
    return min(_ramp(gap, gap_limit),
               _ramp(center_gap, gap_limit + CENTER_GAP_ALLOWANCE))
