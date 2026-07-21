package com.plaiground.domain.game.ninja.domain;

import java.io.Serializable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

// PK(skill_id, seq) — 한 스킬 내 순서 중복을 막는다.
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class SkillGestureId implements Serializable {

    @Column(name = "skill_id")
    private Long skillId;

    @Column(name = "seq")
    private Integer seq;
}
