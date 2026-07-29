package com.camon.domain.game.common.dto;

import com.camon.domain.game.common.Game;

// 대기방 코스 설정 화면이 "고를 수 있는 게임과 그 제약"을 알기 위해 받는 응답.
// 프론트는 이 값으로 라운드 수 스테퍼의 범위와 인원 미달 경고를 만든다.
public record GameCatalogResponse(
    Long gameId,
    // "NINJA" / "FETCH_OBJECT" / "CHARADES" — 식별자 겸 표시값(games.name). 프론트가 아이콘/한글명을
    // 이 값으로 매핑한다. gameId는 시드 순서에 따라 환경마다 달라질 수 있어 신뢰할 수 없다.
    String name,
    String description,
    int minPlayers,
    int maxPlayers,
    // minRounds가 null이면 "참여자 수"가 곧 최소 라운드다(물건 가져오기) — 방 인원에 따라 달라져
    // 서버가 고정값으로 못 준다. 프론트가 현재 인원으로 계산해 하한을 잡는다.
    Integer minRounds,
    Integer maxRounds,
    // 이 게임이 주제(topic)를 골라야 하는 게임인지. true면 코스 항목에 topicId가 필수다.
    boolean requiresTopic,
    // 서버가 이 게임의 세션을 열 수 있는지(GameSessionStarter 구현체가 있는지). false면 목록에는
    // 보이되 코스에 담을 수 없다 — "준비 중"으로 표시하는 용도. 물건 가져오기가 지금 이 상태다.
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
