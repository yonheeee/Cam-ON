import { useEffect, useState } from 'react';
import { aiApi } from '../api/aiApi';

/** 확인 중 / 살아있음 / 꺼져있음. 확인 중과 꺼져있음을 구분해야 "잠깐 깜빡" 하는 잠금을 피할 수 있다. */
export type AiHealth = 'checking' | 'up' | 'down';

/**
 * AI 서버 생존 여부. 물건 가져오기를 코스에 담을 수 있는지 판단하는 데 쓴다.
 *
 * AI 서버는 GPU가 달린 개발용 머신에서 도는 일이 많아(노트북이 곧 인프라다) 꺼져 있는 상태가
 * 흔하다. 그걸 모른 채 코스에 담으면 게임이 시작된 뒤 인식만 실패해서, 참가자들은 "게임이
 * 고장났다"고 느끼고 원인은 화면에 안 나온다. 코스를 짜는 시점에 미리 막는 게 훨씬 싸다.
 *
 * @param enabled false면 확인하지 않는다(모달이 닫혀 있을 때 불필요한 요청을 막는다).
 */
export function useAiHealth(enabled: boolean): AiHealth {
  const [health, setHealth] = useState<AiHealth>('checking');

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    setHealth('checking');
    aiApi.isHealthy().then((ok) => {
      if (!cancelled) setHealth(ok ? 'up' : 'down');
    });
    return () => {
      cancelled = true;
    };
  }, [enabled]);

  return health;
}
