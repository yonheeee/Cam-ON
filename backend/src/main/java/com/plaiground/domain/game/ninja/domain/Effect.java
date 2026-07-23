package com.plaiground.domain.game.ninja.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "effect")
@Getter
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Effect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // hex, 예: #ffd700
    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "particle_count", nullable = false)
    private Integer particleCount;

    @Column(nullable = false)
    private Integer life;

    @Column(name = "radius_min", nullable = false)
    private Integer radiusMin;

    @Column(name = "radius_max", nullable = false)
    private Integer radiusMax;

    @Column(name = "speed_min", nullable = false)
    private Integer speedMin;

    @Column(name = "speed_max", nullable = false)
    private Integer speedMax;
}
