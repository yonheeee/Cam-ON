import { useEffect, useState } from 'react';
import { courseApi, type CatalogGame, type CatalogTopic } from '../api/courseApi';

// 주제를 골라야 하는 게임(몸으로 말해요)의 주제 목록을 gameId별로 모아 둔다.
// 거의 안 바뀌는 정적 데이터라 카탈로그를 받은 뒤 한 번만 조회한다.
export function useTopics(games: CatalogGame[], accessToken: string) {
  const [topicsByGameId, setTopicsByGameId] = useState<Record<number, CatalogTopic[]>>({});

  useEffect(() => {
    const targets = games.filter((game) => game.requiresTopic && game.supported);
    if (targets.length === 0) return;

    let cancelled = false;
    void Promise.all(
      targets.map((game) =>
        courseApi
          .getTopics(game.gameId, accessToken)
          // 한 게임의 주제 조회가 실패해도 나머지 편집은 가능해야 하므로 빈 목록으로 흡수한다.
          .then((topics) => [game.gameId, topics] as const)
          .catch(() => [game.gameId, [] as CatalogTopic[]] as const),
      ),
    ).then((entries) => {
      if (!cancelled) setTopicsByGameId(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [games, accessToken]);

  return topicsByGameId;
}
