package com.camon.domain.game.ninja.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 스킬 ↔ 손동작 순서 매핑. 스킬 하나는 gesture 여러 개를 seq 순서로 이어붙여야 완성되는 콤보다 —
// AI 분류기는 프레임마다 단일 포즈 하나만 판정하지만(9클래스), 그 판정들을 순서대로 이어 붙여
// 완성 여부를 따지는 건 인식 계층(AI/프론트)의 몫이고, 이 테이블은 "어떤 순서로 이어야 하는 스킬인지"를 정의한다.
@Entity
@Table(name = "skill_gesture")
@Getter
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SkillGesture {

    @EmbeddedId
    private SkillGestureId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("skillId")
    @JoinColumn(name = "skill_id")
    private Skill skill;

    // 제스처는 원소처럼 여러 스킬의 여러 시퀀스 단계에서 재사용될 수 있어 다대일 관계.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gesture_id", nullable = false)
    private Gesture gesture;
}
