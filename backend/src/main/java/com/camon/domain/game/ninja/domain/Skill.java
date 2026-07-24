package com.camon.domain.game.ninja.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
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

    // 여러 스킬이 같은 이펙트를 공유할 수 있어 다대일 관계.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "effect_id", nullable = false)
    private Effect effect;

    // seq 순서대로 이어야 완성되는 손동작 콤보. 읽기 전용(이 엔티티 쪽에서 콤보를 편집하지 않음) — skill_gesture가 주인.
    @OneToMany(mappedBy = "skill", fetch = FetchType.LAZY)
    @OrderBy("id.seq ASC")
    @Builder.Default
    private List<SkillGesture> gestures = new ArrayList<>();
}
