package com.camon.domain.game.common.dto;

import com.camon.domain.game.common.Game;

// 대기방 코스 설정 화면이 "고를 수 있는 게임과 그 제약"을 알기 위해 받는 응답.
// 프론트는 이 값으로 인원 미달 경고와 세트 설명을 만든다.
public record GameCatalogResponse(
    Long gameId,
    // "NINJA" / "FETCH_OBJECT" / "CHARADES" — 식별자 겸 표시값(games.name). 프론트가 아이콘/한글명을
    // 이 값으로 매핑한다. gameId는 시드 순서에 따라 환경마다 달라질 수 있어 신뢰할 수 없다.
    String name,
    String description,
    int minPlayers,
    int maxPlayers,
    // 세트당 라운드 수 — 코스가 세트 단위가 되면서 min=max 고정값이다(닌자 1 / 몸말 1 / 물건 5).
    // 클라이언트가 고르는 값이 아니라 표시용 정보다.
    Integer minRounds,
    Integer maxRounds,
    // 이 게임이 주제(topic)를 골라야 하는 게임인지. true면 코스 항목에 topicId가 필수다.
    boolean requiresTopic,
    // 서버가 이 게임의 세션을 열 수 있는지(GameSessionStarter 구현체가 있는지). false면 목록에는
    // 보이되 코스에 담을 수 없다 — "준비 중"으로 표시하는 용도.
    boolean supported
) {

    public static GameCatalogResponse of(
        Game game,
        boolean requiresTopic,
        boolean supported
    ) {
        return new GameCatalogResponse(
            game.getGameId(),
            game.getName(),
            game.getDescription(),
            game.getMinPlayers(),
            game.getMaxPlayers(),
            game.getMinRounds(),
            game.getMaxRounds(),
            requiresTopic,
            supported
        );
    }
}
