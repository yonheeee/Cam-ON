package com.plaiground.domain.game.ninja.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "skill")
@Getter
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "skill_desc")
    private String skillDesc;

    @Column(nullable = false)
    private Integer damage;

    // AI 분류기 자체가 9클래스 단일 포즈 판정이라 스킬:손동작은 1:1 — 시퀀스 조인 테이블(skill_gesture) 대신 직접 FK.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gesture_id", nullable = false, unique = true)
    private Gesture gesture;

    // 여러 스킬이 같은 이펙트를 공유할 수 있어 다대일 관계.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effect_id", nullable = false)
    private Effect effect;
}
