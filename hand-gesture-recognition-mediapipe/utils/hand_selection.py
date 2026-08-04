#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""여러 개 잡힌 손 후보 중 판정에 쓸 왼손/오른손 한 쌍을 고르는 모듈.

왜 필요한가: MediaPipe Hands는 "가까운 손"이나 "화면 중앙의 손"을 골라주지 않는다. max_num_hands
상한까지 자기 detection 점수 순으로 내놓을 뿐이라, 뒤쪽을 지나가는 사람의 손이나 손처럼 생긴
물체가 움직이면 그쪽이 상위에 올라오면서 정작 플레이어의 손 하나가 밀려난다. 양손 조합 포즈만
쓰는 닌자에서는 손 하나만 밀려도 판정이 통째로 죽는다.

그래서 max_num_hands를 넉넉히 잡아 후보를 여러 개 받고, 그중 (1) 카메라와 충분히 가까운 손만
남긴 뒤 (2) 가장 크고 가운데에 있는 손과 그 근처의 반대쪽 손을 짝으로 고른다.

"z축"에 대해: MediaPipe의 landmark.z는 카메라와의 거리가 아니다 — 같은 손의 손목을 원점으로 한
손 안에서의 상대 깊이라서, 손을 멀리 가져가도 값이 그만큼 커지지 않는다. 카메라 거리를 알려주는
건 결국 손이 화면에서 얼마나 크게 보이는가뿐이다(겉보기 크기 ∝ 1/거리). 그래서 깊이 하한은
z값이 아니라 "프레임 높이 대비 손바닥 길이"(아래 depth) 하한으로 잰다.

프론트엔드 구현(frontend/src/features/gesture/lib/handSelection.ts)과 같은 기준·같은 상수를
쓴다 — 한쪽을 바꾸면 다른 쪽도 맞춰야 판정이 어긋나지 않는다.
"""
import math
from typing import NamedTuple

# 손목(0)에서 각 손가락 MCP까지 — 손바닥 뼈대라 손가락을 접어도(주먹) 길이가 안 변한다.
PALM_SPAN_INDICES = (5, 9, 17)

# 판정에 쓸 손의 깊이 하한 = 프레임 높이 대비 손바닥 길이. 손목~MCP는 성인 손에서 약 9cm라,
# 세로 화각 40°짜리 웹캠이면 60cm 거리에서 0.21, 1.2m에서 0.10, 2m 넘으면 0.06 아래가 된다.
# 0.09는 "팔을 뻗은 플레이어(~1.3m)까지 통과, 뒤쪽을 지나가는 사람(2m+)은 차단"에 해당하는 선.
MIN_HAND_DEPTH = 0.09

# 이미 잡고 있던 손은 하한의 이 비율까지 계속 인정한다(경계에서 깜빡이는 것 방지).
DEPTH_RELEASE_RATIO = 0.85

# 중앙에서 벗어난 만큼 점수를 곱셈으로 깎는 비율. "큰 손 우선"이 기본이고 중앙 여부는 크기가
# 비슷할 때 갈리는 기준이 되게 한다 — 0.5면 가장자리 손은 중앙 손보다 2배 이상 커야 이긴다.
CENTER_PENALTY = 0.5

# 짝 손을 고를 때 주 손에서 떨어진 거리에 매기는 감쇠 기준(손바닥 길이 단위). 가장 많이 벌어지는
# cat의 실측 거리가 손바닥 4개분이라 그 값을 기준점으로 삼았다 — 이만큼 떨어지면 점수가 절반.
PARTNER_GAP_TOLERANCE = 4


class HandCandidate(NamedTuple):
    handedness: str
    # 이 모듈이 들여다보지 않고 고른 손과 함께 그대로 돌려주는 원본(호출부가 쓰던 형태 그대로)
    source: object
    # 픽셀 좌표 (스무딩 전). 거리/크기 계산은 축 배율이 같은 픽셀 공간에서 해야 왜곡이 없다
    pixels: list
    palm_span: float
    center: list
    # 프레임 높이 대비 손바닥 길이 — 카메라와의 거리 대용 지표 (클수록 가까움)
    depth: float
    # 프레임 중심에서 손 중심까지 거리 (프레임 높이 단위, 0 = 정중앙)
    center_offset: float
    # 가까움 × 가운데 우선 점수
    score: float


class HandSelection(NamedTuple):
    # 판정에 쓸 손 — 최대 2개(왼손/오른손 각 1개)
    hands: list
    # 이번 프레임에서 가장 가까운(=크게 보이는) 손의 depth. 후보가 없으면 None
    nearest_depth: float
    # 깊이 하한에 걸려 버린 손 개수
    far_hand_count: int


def _distance(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _palm_scale(pixels):
    wrist = pixels[0]
    spans = [_distance(wrist, pixels[i]) for i in PALM_SPAN_INDICES]
    return sum(spans) / len(spans)


def _centroid(pixels):
    xs = [p[0] for p in pixels]
    ys = [p[1] for p in pixels]
    return [sum(xs) / len(xs), sum(ys) / len(ys)]


def describe_hand_candidate(handedness, source, pixels, frame_width, frame_height):
    """후보 하나의 크기/위치 지표를 계산한다. 손바닥 크기를 못 구하면 None(깊이를 판단할 근거가
    없는 손이라 후보에서 제외한다).

    x·y 모두 프레임 **높이**로만 정규화한다 — 픽셀 공간은 두 축 배율이 같으므로 한쪽 축으로
    나눠야 거리 비교가 왜곡되지 않는다.
    """
    palm_span = _palm_scale(pixels)
    if not math.isfinite(palm_span) or palm_span <= 0 or frame_height <= 0:
        return None

    center = _centroid(pixels)
    depth = palm_span / frame_height
    center_offset = _distance(center, [frame_width / 2, frame_height / 2]) / frame_height
    score = depth * (1 - CENTER_PENALTY * min(center_offset, 1))

    return HandCandidate(handedness=handedness, source=source, pixels=pixels,
                         palm_span=palm_span, center=center, depth=depth,
                         center_offset=center_offset, score=score)


def _partner_score(primary, candidate):
    gap_in_palms = _distance(primary.center, candidate.center) / primary.palm_span
    return candidate.score / (1 + gap_in_palms / PARTNER_GAP_TOLERANCE)


def select_combo_hands(candidates, engaged):
    """후보들 중 판정에 쓸 왼손/오른손 한 쌍을 고른다.

    engaged: 직전 프레임에서 손을 잡고 있었는가 — 깊이 하한에 이력(hysteresis)을 주는 데 쓴다.
    """
    nearest_depth = max((c.depth for c in candidates), default=None)

    threshold = MIN_HAND_DEPTH * DEPTH_RELEASE_RATIO if engaged else MIN_HAND_DEPTH
    near = [c for c in candidates if c.depth >= threshold]
    far_hand_count = len(candidates) - len(near)
    if not near:
        return HandSelection(hands=[], nearest_depth=nearest_depth,
                             far_hand_count=far_hand_count)

    # 주 손: 가장 크고 중앙에 가까운 손.
    ranked = sorted(near, key=lambda c: c.score, reverse=True)
    primary = ranked[0]

    # 짝 손: 반대쪽 손 중 (크기·중앙 점수 ÷ 주 손과의 거리)가 가장 높은 것. 같은 쪽 손이 둘
    # 잡히는 오인식도 있으므로 handedness가 다른 후보만 본다 — 84차원 조합 입력은 왼손 슬롯과
    # 오른손 슬롯에 각각 하나씩 들어가야 학습 때와 같은 매핑이 된다.
    partners = [c for c in ranked[1:] if c.handedness != primary.handedness]
    hands = [primary]
    if partners:
        hands.append(max(partners, key=lambda c: _partner_score(primary, c)))

    return HandSelection(hands=hands, nearest_depth=nearest_depth,
                         far_hand_count=far_hand_count)
