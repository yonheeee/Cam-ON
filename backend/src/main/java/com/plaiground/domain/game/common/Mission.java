package com.plaiground.domain.game.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "missions")
@Getter
@EqualsAndHashCode(of = "missionId")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mission_id")
    private Long missionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    // HAND_POSE / OBJECT / CHARADES. HAND_POSE는 이제 아예 안 쓰임 — 닌자 라운드가 요구하는 건
    // skill 하나이고, round:{n}이 mission_id 대신 skill_id를 직접 참조한다.
    @Column(name = "mission_type", nullable = false)
    private String missionType;

    @Column
    private String keyword;

    @Column(name = "target_label")
    private String targetLabel;

    @Column(nullable = false)
    private String difficulty;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
