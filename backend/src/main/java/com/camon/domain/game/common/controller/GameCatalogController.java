package com.camon.domain.game.common.controller;

import com.camon.domain.game.common.dto.GameCatalogResponse;
import com.camon.domain.game.common.dto.MissionTopicResponse;
import com.camon.domain.game.common.service.GameCatalogService;
import com.camon.global.apiresponse.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 대기방 코스 설정 화면이 "무엇을 고를 수 있는지"를 받아가는 조회 전용 엔드포인트.
// 방에 속하지 않은 정적 데이터라 roomId를 받지 않는다(방 참가자 검증도 불필요 —
// 인증만 통과하면 누구나 게임 목록을 볼 수 있다).
@RestController
@RequestMapping("/api/games")
public class GameCatalogController {

    private final GameCatalogService gameCatalogService;

    public GameCatalogController(GameCatalogService gameCatalogService) {
        this.gameCatalogService = gameCatalogService;
    }

    @GetMapping
    public ApiResponse<List<GameCatalogResponse>> getGames() {
        return ApiResponse.ok(gameCatalogService.findSelectableGames());
    }

    @GetMapping("/{gameId}/topics")
    public ApiResponse<List<MissionTopicResponse>> getTopics(
        @PathVariable Long gameId
    ) {
        return ApiResponse.ok(gameCatalogService.findTopics(gameId));
    }
}
