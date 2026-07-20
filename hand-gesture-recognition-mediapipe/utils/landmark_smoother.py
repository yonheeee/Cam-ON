#!/usr/bin/env python
# -*- coding: utf-8 -*-
import math


def _smoothing_factor(t_e, cutoff):
    r = 2 * math.pi * cutoff * t_e
    return r / (r + 1)


class _LowPassFilter:
    def __init__(self):
        self.y = 0.0
        self.initialized = False

    def filter(self, value, alpha):
        if not self.initialized:
            self.y = value
            self.initialized = True
        else:
            self.y = alpha * value + (1.0 - alpha) * self.y
        return self.y


class OneEuroFilter:
    """1€ 필터(Casiez et al. 2012): 정지/저속 구간은 강하게 스무딩해서 떨림을 줄이고,
    빠르게 움직일 땐 컷오프를 올려 지연(lag)이 커지지 않게 한다.
    손가락이 겹쳐서 검출이 흔들릴 때(정지 상태에 가까움)를 완화하는 데 적합하다."""

    def __init__(self, min_cutoff=1.0, beta=0.0, d_cutoff=1.0):
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self.x_filter = _LowPassFilter()
        self.dx_filter = _LowPassFilter()
        self.last_time = None

    def __call__(self, t, x):
        if self.last_time is None:
            t_e = 1.0
        else:
            t_e = max(t - self.last_time, 1e-6)
        self.last_time = t

        prev_x = self.x_filter.y if self.x_filter.initialized else x
        dx = (x - prev_x) / t_e
        dx_hat = self.dx_filter.filter(dx, _smoothing_factor(t_e, self.d_cutoff))

        cutoff = self.min_cutoff + self.beta * abs(dx_hat)
        return self.x_filter.filter(x, _smoothing_factor(t_e, cutoff))


class HandLandmarkSmoother:
    """손(Left/Right)별로 21개 랜드마크의 x, y 각각에 독립적인 1€ 필터를 적용한다.
    한쪽 손이 화면에서 사라졌다 다시 잡히면 이전 값으로 이어붙이지 않도록 reset()으로
    해당 손의 필터 상태를 버려야 한다(안 그러면 사라지기 직전 위치에서 스냅되며 튐)."""

    def __init__(self, min_cutoff=1.0, beta=0.3, d_cutoff=1.0):
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self._filters = {'Left': None, 'Right': None}

    def smooth(self, hand_label, landmark_list, timestamp):
        if self._filters[hand_label] is None:
            self._filters[hand_label] = [
                (OneEuroFilter(self.min_cutoff, self.beta, self.d_cutoff),
                 OneEuroFilter(self.min_cutoff, self.beta, self.d_cutoff))
                for _ in landmark_list
            ]

        filters = self._filters[hand_label]
        smoothed = []
        for (filter_x, filter_y), (x, y) in zip(filters, landmark_list):
            smoothed.append([
                int(round(filter_x(timestamp, x))),
                int(round(filter_y(timestamp, y))),
            ])
        return smoothed

    def reset(self, hand_label):
        self._filters[hand_label] = None
