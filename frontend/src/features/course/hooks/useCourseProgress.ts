import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useEffect, useState } from 'react';

// 코스 진행 상태를 구독하는 훅. 방 하나의 /topic/rooms/{roomId}에서 코스 관련 이벤트만 골라 본다.
//
// - game:started        코스의 다음 게임이 열렸다 (게임별 패널 전환의 유일한 신호)
// - course:session-skipped  인원이 안 맞아 그 게임을 건너뛰었다
// - course:finished     코스의 마지막 게임까지 끝났다 → 종합 결과
// - course:member-returned  누군가 종합 결과에서 대기방 복귀를 눌렀다. 복귀는 개별 행동이라
//                       내 id일 때만 결과 화면을 접는다(남의 복귀로 내 화면이 넘어가면 안 된다).
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

export interface MemberReturnedData {
  participantId: string;
  ready: boolean;
  /** 이 복귀가 방을 WAITING으로 되돌렸는가(= 가장 먼저 누른 사람인가) */
  roomReopened: boolean;
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
  /** 내 participantId — 복귀 이벤트가 내 것인지 가려내 내 화면만 전환한다 */
  participantId: string,
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
      // STOMP는 인증 실패에도 reconnectDelay로 재연결을 계속 시도한다 — 죽은 토큰으로는
      // 영원히 실패하므로, 세션을 정리하고 첫 화면으로 되돌려 루프를 끊는다.
      onStompError: () => handleExpiredSession(),
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
            case 'course:member-returned': {
              // 복귀는 개별 행동이다 — 돌아간 사람이 나일 때만 결과 화면을 접고 대기방으로
              // 전환한다(대기방이 스냅샷을 새로 읽는다). 남이 돌아간 건 내 화면과 무관하고,
              // 대기방 쪽 훅(useRoomLobby)이 그 사람 타일 표시만 갱신한다.
              const data = event.data as MemberReturnedData;
              if (data.participantId !== participantId) break;
              setFinished(null);
              setActiveSession(null);
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
  }, [roomId, accessToken, participantId]);

  return {
    activeSession,
    finished,
    skipped,
    clearSkipped: () => setSkipped(null),
  };
}
