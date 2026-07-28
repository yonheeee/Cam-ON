package com.camon.domain.game.common;

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
@Table(name = "games")
@Getter
@EqualsAndHashCode(of = "gameId")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "game_id")
    private Long gameId;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "min_players", nullable = false)
    private Integer minPlayers;

    @Column(name = "max_players", nullable = false)
    private Integer maxPlayers;

    // null이면 애플리케이션이 "참여자 수"를 최소 라운드로 계산한다(물건가져오기).
    @Column(name = "min_rounds")
    private Integer minRounds;

    @Column(name = "max_rounds")
    private Integer maxRounds;

    // 삭제 대신 두는 노출 토글 — false면 대기방 코스 설정 화면 목록에서만 빠지고 row는 남는다.
    // room:{code}:course:{idx}가 game_id를 FK 제약 없이 값으로만 참조하고 있어서, row를 진짜 지우면
    // 이미 만들어진 방의 참조가 끊긴다.
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
