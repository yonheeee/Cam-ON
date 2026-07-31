import { Client } from '@stomp/stompjs';
import { useEffect, useState } from 'react';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import type { CourseRankRow, SetResultRow } from '../components/SetResultScreen';

// 코스의 게임 한 세트가 끝난 순간을 잡아 중간 결과 화면에 쓸 데이터를 만드는 훅.
//
// 백엔드에는 "세트가 끝났다"는 공통 이벤트가 없다 — 게임마다 자기 종료 이벤트를 쏜다
// (ninja:game-ended / charades:game-ended / game:end). 그래서 여기서 셋을 한 모양으로 정규화한다.
//
// ponytail: 코스 누적 점수도 프론트에서 세트별 점수를 더해 만든다. 서버가 누적을 알려주는 건
// course:finished뿐이라 다른 방법이 없다. 한계 — 게임 도중 새로고침하거나 늦게 들어온 사람은
// 이전 세트 점수를 못 봤으므로 누적이 실제보다 작게 나온다(등수 자체는 그 세트 기준으로 맞다).
// 백엔드에 course:session-finished(세트 순위 + 코스 누적)가 생기면 이 누적 로직은 통째로 지운다.

interface FinishedSet {
  sessionSeq: number;
  /** 세트가 끝난 시각(epoch ms) — 자동 진행까지 남은 시간을 세는 기준 */
  endedAt: number;
  setResult: SetResultRow[];
  courseRanking: CourseRankRow[];
}

interface NormalizedEntry {
  participantId: string;
  score: number;
  rank: number;
}

interface NinjaEndedData {
  ranking: { token: string; rank: number }[];
  sessionTotals: Record<string, number>;
}

interface CharadesEndedData {
  ranking: { participantId: string; totalScore: number; rank: number }[];
}

interface FetchEndedData {
  scores: { participantId: string; score: number; rank: number }[];
}

// 점수 내림차순 정렬 + 동점 같은 순위 (백엔드 CourseRunner.buildRanking과 같은 규칙).
function rankByScore(totals: Map<string, number>): Map<string, number> {
  const sorted = [...totals.entries()].sort((a, b) => b[1] - a[1]);
  const ranks = new Map<string, number>();
  let previousScore = Number.NaN;
  let previousRank = 0;
  sorted.forEach(([participantId, score], index) => {
    const rank = score === previousScore ? previousRank : index + 1;
    ranks.set(participantId, rank);
    previousScore = score;
    previousRank = rank;
  });
  return ranks;
}

// 닉네임은 여기서 붙이지 않는다 — participantId만 담아 두고 화면이 그릴 때 조회한다.
// (이벤트가 도착한 순간 이름을 문자열로 박아두면, 그 시점에 아직 모르던 사람 — 늦게 입장해서
//  방 스냅샷에 없던 사람 — 이 나중에 이름을 알게 돼도 영영 "알 수 없음"으로 남는다.)
export function useSetResult(roomId: string, accessToken: string): FinishedSet | null {
  const [finishedSet, setFinishedSet] = useState<FinishedSet | null>(null);

  useEffect(() => {
    // 세트별 점수. 같은 이벤트가 두 번 와도(재연결) 세트 번호로 덮어써서 이중 계산을 막는다.
    const scoresBySeq = new Map<number, Map<string, number>>();
    // game-ended payload에는 세션 번호가 없다 — game:started로 따라간다.
    let currentSeq = 0;

    const publish = (entries: NormalizedEntry[]) => {
      if (entries.length === 0) return;
      const seq = currentSeq;
      // 이번 세트를 빼고 계산한 직전 누적 순위 — 화살표(▲▼)의 기준
      const before = new Map<string, number>();
      for (const [otherSeq, scores] of scoresBySeq) {
        if (otherSeq === seq) continue;
        for (const [id, score] of scores) before.set(id, (before.get(id) ?? 0) + score);
      }

      scoresBySeq.set(seq, new Map(entries.map((e) => [e.participantId, e.score])));

      const after = new Map(before);
      for (const entry of entries) {
        after.set(entry.participantId, (after.get(entry.participantId) ?? 0) + entry.score);
      }
      // 이번 세트에 점수가 없던 사람도 표에 남아야 한다(0점으로).
      for (const id of before.keys()) if (!after.has(id)) after.set(id, before.get(id) ?? 0);

      const beforeRanks = rankByScore(before);
      const afterRanks = rankByScore(after);

      setFinishedSet({
        sessionSeq: seq,
        endedAt: Date.now(),
        setResult: [...entries]
          .sort((a, b) => a.rank - b.rank)
          .map((entry) => ({
            participantId: entry.participantId,
            score: entry.score,
            rank: entry.rank,
          })),
        courseRanking: [...after.entries()]
          .sort((a, b) => b[1] - a[1])
          .map(([participantId, totalScore]) => {
            const previous = beforeRanks.get(participantId);
            const current = afterRanks.get(participantId) ?? 1;
            return {
              participantId,
              totalScore,
              rank: current,
              // 첫 세트엔 비교할 직전 순위가 없다 — 움직임 없음으로 둔다.
              delta:
                previous === undefined || previous === current
                  ? 'same'
                  : current < previous
                    ? 'up'
                    : 'down',
            } satisfies CourseRankRow;
          }),
      });
    };

    const defaultProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const baseUrl =
      import.meta.env.VITE_WS_BASE_URL ?? `${defaultProtocol}//${window.location.hostname}:8080`;
    const client = new Client({
      brokerURL: `${baseUrl}/ws/rooms/${roomId}`,
      connectHeaders: { Authorization: `Bearer ${accessToken}` },
      reconnectDelay: 3000,
      onStompError: () => handleExpiredSession(),
      onConnect: () => {
        client.subscribe(`/topic/rooms/${roomId}`, (message) => {
          const event = JSON.parse(message.body) as { event?: string; data?: unknown };
          switch (event.event) {
            case 'game:started': {
              currentSeq = (event.data as { sessionSeq: number }).sessionSeq;
              // 다음 세트가 열렸으니 중간 결과는 접는다.
              setFinishedSet(null);
              break;
            }
            case 'course:finished':
            case 'course:reset':
              // 종합 결과(또는 대기방)가 이 화면을 대신한다.
              setFinishedSet(null);
              break;
            case 'ninja:game-ended': {
              const data = event.data as NinjaEndedData;
              publish(
                data.ranking.map((entry) => ({
                  participantId: entry.token,
                  score: data.sessionTotals[entry.token] ?? 0,
                  rank: entry.rank,
                })),
              );
              break;
            }
            case 'charades:game-ended': {
              const data = event.data as CharadesEndedData;
              publish(
                data.ranking.map((entry) => ({
                  participantId: entry.participantId,
                  score: entry.totalScore,
                  rank: entry.rank,
                })),
              );
              break;
            }
            case 'game:end': {
              // 물건 가져오기 — 이름이 게임 접두사 없이 나간다(useFetchRound와 같은 이벤트).
              const data = event.data as FetchEndedData;
              publish(data.scores.map((entry) => ({ ...entry })));
              break;
            }
            default:
              break;
          }
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);

  return finishedSet;
}
