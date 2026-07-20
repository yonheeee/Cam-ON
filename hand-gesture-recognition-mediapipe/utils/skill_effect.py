#!/usr/bin/env python
# -*- coding: utf-8 -*-
import math
import random

import cv2 as cv


class SkillEffect(object):
    """특정 제스처가 인식됐을 때 손 위치에서 터지는 간단한 파티클 이펙트."""

    def __init__(self, life=20, particle_count=28, radius_range=(2, 5),
                speed_range=(4, 11)):
        self.life = life
        self.particle_count = particle_count
        self.radius_range = radius_range
        self.speed_range = speed_range
        self.particles = []  # [x, y, vx, vy, age, radius, color]

    def spawn(self, center, color=(0, 215, 255)):
        cx, cy = center
        for _ in range(self.particle_count):
            angle = random.uniform(0, 2 * math.pi)
            speed = random.uniform(*self.speed_range)
            vx, vy = math.cos(angle) * speed, math.sin(angle) * speed
            radius = random.uniform(*self.radius_range)
            self.particles.append([cx, cy, vx, vy, 0, radius, color])

    def update_and_draw(self, image):
        if not self.particles:
            return image

        overlay = image.copy()
        alive = []
        for x, y, vx, vy, age, radius, color in self.particles:
            x += vx
            y += vy
            vy += 0.3  # 살짝 중력을 줘서 포물선으로 퍼지게
            age += 1
            if age < self.life:
                fade = 1 - age / self.life
                r = max(1, int(radius * fade))
                cv.circle(overlay, (int(x), int(y)), r, color, -1, cv.LINE_AA)
                alive.append([x, y, vx, vy, age, radius, color])
        self.particles = alive

        return cv.addWeighted(overlay, 0.65, image, 0.35, 0)
