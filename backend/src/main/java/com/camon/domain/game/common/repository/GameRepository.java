package com.camon.domain.game.common.repository;

import com.camon.domain.game.common.Game;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// games 테이블을 읽는 유일한 경로. 코스(대기방에서 확정하는 게임 순서)가 game_id를 값으로만
// 참조하기 때문에, "그 game_id가 실제로 존재하고 지금 선택 가능한가"를 검증하는 데도 쓴다.
public interface GameRepository extends JpaRepository<Game, Long> {

    // 코스 설정 화면에 뿌릴 목록. is_active=false는 노출 토글이 꺼진 것이라 목록에서 빠진다
    // (row는 남아 있어서 이미 만들어진 방의 course 참조는 깨지지 않는다).
    List<Game> findAllByIsActiveTrueOrderByGameIdAsc();

    Optional<Game> findByGameIdAndIsActiveTrue(Long gameId);

    // 세션 시작 전략을 게임 이름("NINJA"/"CHARADES"/"FETCH_OBJECT")으로 고르기 위한 조회.
    // game_id는 시드 순서에 따라 환경마다 달라질 수 있어 하드코딩할 수 없다.
    Optional<Game> findByName(String name);
}
