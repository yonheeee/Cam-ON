import { useEffect, useMemo, useState } from 'react';
import { courseApi, type CatalogGame, type GameName } from '../api/courseApi';

// 게임 카탈로그만 읽는 가벼운 훅(WS 구독 없음). gameId → games.name 매핑이 필요한 곳에서 쓴다.
//
// 게임 화면을 gameId로 분기하면 안 되는 이유: gameId는 시드 순서에 따라 환경마다 달라질 수 있다.
// game:started가 알려주는 gameId를 카탈로그로 이름으로 바꿔서 분기한다.
export function useGameCatalog(accessToken: string) {
  const [games, setGames] = useState<CatalogGame[]>([]);

  useEffect(() => {
    let cancelled = false;
    courseApi
      .getGames(accessToken)
      .then((loaded) => {
        if (!cancelled) setGames(loaded);
      })
      .catch(() => {
        // 조회 실패 시 이름을 알 수 없다 — 호출자가 null 처리한다(게임 화면이 안 뜨는 것보다
        // 낫게, 상위에서 기본 화면으로 폴백한다).
      });
    return () => {
      cancelled = true;
    };
  }, [accessToken]);

  const gameNameById = useMemo(
    () => new Map(games.map((game) => [game.gameId, game.name])),
    [games],
  );

  return {
    games,
    gameNameOf: (gameId: number | null | undefined): GameName | null =>
      gameId == null ? null : (gameNameById.get(gameId) ?? null),
  };
}
