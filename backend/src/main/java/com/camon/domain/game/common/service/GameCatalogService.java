package com.camon.domain.game.common.service;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.dto.GameCatalogResponse;
import com.camon.domain.game.common.dto.MissionTopicResponse;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 게임 카탈로그 조회. 거의 안 바뀌는 정적 데이터(games / mission_topics)를 읽기만 한다.
//
// 코스 설정 화면이 존재하기 전까지 games 테이블은 코드에서 단 한 번도 조회되지 않았다 —
// 이 서비스가 첫 소비자다. 방장이 "무엇을 몇 라운드로 넣을 수 있는지" 알려면 이 정보가 필요하다.
@Service
public class GameCatalogService {

    private final GameRepository gameRepository;
    private final MissionTopicRepository missionTopicRepository;
    private final MissionRepository missionRepository;
    // games 테이블에 row가 있다는 것과 서버가 그 게임을 실제로 시작할 수 있다는 것은 다른 얘기다.
    // 구현 여부의 유일한 증거는 "그 이름의 GameSessionStarter 빈이 있는가"이므로 여기서 모아 둔다.
    private final Set<String> supportedGameNames;

    public GameCatalogService(
        GameRepository gameRepository,
        MissionTopicRepository missionTopicRepository,
        MissionRepository missionRepository,
        List<GameSessionStarter> sessionStarters
    ) {
        this.gameRepository = gameRepository;
        this.missionTopicRepository = missionTopicRepository;
        this.missionRepository = missionRepository;
        this.supportedGameNames = sessionStarters.stream()
            .map(GameSessionStarter::gameName)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public List<GameCatalogResponse> findSelectableGames() {
        return gameRepository.findAllByIsActiveTrueOrderByGameIdAsc().stream()
            .map(game -> GameCatalogResponse.of(
                game,
                hasTopics(game.getGameId()),
                isSupported(game.getName())
            ))
            .toList();
    }

    public boolean isSupported(String gameName) {
        return supportedGameNames.contains(gameName);
    }

    @Transactional(readOnly = true)
    public List<MissionTopicResponse> findTopics(Long gameId) {
        // 없는/비활성 게임의 주제를 묻는 건 잘못된 요청이라 빈 목록이 아니라 404로 끊는다 —
        // 프론트가 "주제가 0개인 게임"과 "게임 자체가 없음"을 구분할 수 있어야 한다.
        requireSelectableGame(gameId);
        return missionTopicRepository
            .findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(gameId).stream()
            .map(topic -> MissionTopicResponse.of(
                topic,
                missionRepository.countByTopicTopicIdAndIsActiveTrue(topic.getTopicId())
            ))
            .toList();
    }

    public Game requireSelectableGame(Long gameId) {
        return gameRepository.findByGameIdAndIsActiveTrue(gameId)
            .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
    }

    // 표시용 게임 이름. 비활성화된 게임도 이름은 돌려준다 — 이미 코스에 담긴 게임이 나중에
    // 비활성화돼도 대기방 코스 목록이 "이름 없는 칸"으로 깨지지 않게.
    @Transactional(readOnly = true)
    public String findName(Long gameId) {
        return gameRepository.findById(gameId).map(Game::getName).orElse(null);
    }

    // "이 게임은 주제를 골라야 하는 게임인가"를 게임 이름 하드코딩 대신 데이터로 판단한다 —
    // 주제가 등록된 게임(= 몸으로 말해요)이면 코스 항목에 topicId가 필수다.
    public boolean hasTopics(Long gameId) {
        return !missionTopicRepository
            .findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(gameId)
            .isEmpty();
    }
}
