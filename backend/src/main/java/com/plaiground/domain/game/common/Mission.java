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

    // HAND_POSE / OBJECT / CHARADES. HAND_POSE는 실제 콤보 판정엔 안 쓰이고
    // skill_gesture 쪽 데이터가 그 역할을 대신한다 — round:{n}은 mission_id 대신 skill_id를 직접 참조.
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
