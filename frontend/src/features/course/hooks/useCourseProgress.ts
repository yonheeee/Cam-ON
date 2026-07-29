import { Client } from '@stomp/stompjs';
import { useEffect, useState } from 'react';

// 코스 진행 상태를 구독하는 훅. 방 하나의 /topic/rooms/{roomId}에서 코스 관련 이벤트만 골라 본다.
//
// - game:started        코스의 다음 게임이 열렸다 (게임별 패널 전환의 유일한 신호)
// - course:session-skipped  인원이 안 맞아 그 게임을 건너뛰었다
// - course:finished     코스의 마지막 게임까지 끝났다 → 종합 결과
interface RoomEvent<T> {
  event: string;
  data: T;
}

export interface GameStartedData {
  gameId: number;
  sessionSeq: number;
  totalRounds: number;
}

export interface CourseScoreEntry {
  participantId: string;
  totalScore: number;
  rank: number;
}

export interface CourseFinishedData {
  totalSessions: number;
  ranking: CourseScoreEntry[];
}

export interface SessionSkippedData {
  sessionSeq: number;
  gameId: number;
  gameName: string | null;
  reason: string;
}

interface UseCourseProgressResult {
  /** 지금 열려 있는 게임. null이면 아직 게임이 시작되지 않았다 */
  activeSession: GameStartedData | null;
  /** 코스가 전부 끝났을 때의 종합 결과. null이면 아직 진행 중 */
  finished: CourseFinishedData | null;
  /** 방금 건너뛴 게임 안내 (표시 후 호출자가 지운다) */
  skipped: SessionSkippedData | null;
  clearSkipped: () => void;
}

export function useCourseProgress(
  roomId: string,
  accessToken: string,
): UseCourseProgressResult {
  const [activeSession, setActiveSession] = useState<GameStartedData | null>(null);
  const [finished, setFinished] = useState<CourseFinishedData | null>(null);
  const [skipped, setSkipped] = useState<SessionSkippedData | null>(null);

  useEffect(() => {
    const defaultProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const baseUrl =
      import.meta.env.VITE_WS_BASE_URL ?? `${defaultProtocol}//${window.location.hostname}:8080`;
    const client = new Client({
      brokerURL: `${baseUrl}/ws/rooms/${roomId}`,
      connectHeaders: { Authorization: `Bearer ${accessToken}` },
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe(`/topic/rooms/${roomId}`, (message) => {
          const event = JSON.parse(message.body) as RoomEvent<unknown>;
          switch (event.event) {
            case 'game:started':
              // 코스의 첫 게임이든 다음 게임이든 같은 이벤트로 온다 — 세션 정보를 갈아끼우면
              // 상위에서 그에 맞는 게임 패널로 전환된다.
              setActiveSession(event.data as GameStartedData);
              setSkipped(null);
              break;
            case 'course:session-skipped':
              setSkipped(event.data as SessionSkippedData);
              break;
            case 'course:finished':
              setFinished(event.data as CourseFinishedData);
              setActiveSession(null);
              break;
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

  return {
    activeSession,
    finished,
    skipped,
    clearSkipped: () => setSkipped(null),
  };
}
