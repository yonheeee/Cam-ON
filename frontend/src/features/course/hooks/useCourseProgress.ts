import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useEffect, useState } from 'react';

// 코스 진행 상태를 구독하는 훅. 방 하나의 /topic/rooms/{roomId}에서 코스 관련 이벤트만 골라 본다.
//
// - game:started        코스의 다음 게임이 열렸다 (게임별 패널 전환의 유일한 신호)
// - course:intermission 게임이 열리기 전 대기가 시작됐다 — 다음 게임 룰 설명과 자동 재개 시각이
//                       온다. 코스 첫 게임 앞에도 온다(방장이 "게임 시작"을 누른 직후).
// - course:aborted      코스를 시작했지만 첫 게임을 열지 못해 대기방으로 되돌아갔다
// - course:session-skipped  인원이 안 맞아 그 게임을 건너뛰었다
// - course:finished     코스의 마지막 게임까지 끝났다 → 종합 결과
// - course:member-returned  누군가 종합 결과에서 대기방 복귀를 눌렀다. 복귀는 개별 행동이라
//                       내 id일 때만 결과 화면을 접는다(남의 복귀로 내 화면이 넘어가면 안 된다).
//                       남이 돌아간 경우엔 returnedParticipantIds에 쌓아, 이미 대기방으로 간
//                       사람의 캠이 결과 화면에 계속 떠 있지 않게 한다.
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

// 게임 사이 대기 안내. "다음에 뭘 하는지"는 서버가 고른다 — 인원이 안 맞는 코스 칸은 건너뛰므로
// 프론트가 코스만 보고 다음 게임을 알 수 없다. 룰 설명(nextGameDescription)의 원본은 MySQL
// games.description이라 프론트에 문구를 두지 않는다.
export interface IntermissionData {
  /** 직전에 끝난 게임의 seq. 코스 첫 게임 앞 인터미션은 끝난 게임이 없어 0이다 */
  finishedSessionSeq: number;
  nextSessionSeq: number | null;
  nextGameId: number | null;
  nextGameName: string | null;
  nextGameDescription: string | null;
  nextRoundCount: number | null;
  /** 이 시각에 다음 게임이 자동으로 열린다 (ISO 8601) */
  resumesAt: string;
  /** 방장이 대기를 건너뛸 수 있는가. 남은 게임이 없으면 false */
  skippable: boolean;
}

interface UseCourseProgressResult {
  /** 지금 열려 있는 게임. null이면 아직 게임이 시작되지 않았다 */
  activeSession: GameStartedData | null;
  /** 코스가 전부 끝났을 때의 종합 결과. null이면 아직 진행 중 */
  finished: CourseFinishedData | null;
  /** 진행 중인 게임 사이 대기 안내. 다음 게임이 열리거나 코스가 끝나면 null로 돌아간다 */
  intermission: IntermissionData | null;
  /**
   * 종합 결과에서 이미 대기방으로 돌아간 사람들. 결과 화면과 대기방은 서로 다른 화면이라
   * 캠이 양쪽에 동시에 떠 있으면 안 된다 — 결과 화면은 이 집합에 든 사람의 캠을 그리지 않는다.
   * 코스가 새로 끝날 때마다(course:finished) 비운다.
   */
  returnedParticipantIds: Set<string>;
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
  const [intermission, setIntermission] = useState<IntermissionData | null>(null);
  const [skipped, setSkipped] = useState<SessionSkippedData | null>(null);
  const [returnedParticipantIds, setReturnedParticipantIds] = useState<Set<string>>(
    () => new Set(),
  );

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
              // 다음 게임이 열렸으니 대기 안내는 내린다(방장이 건너뛴 경우도 이 경로로 닫힌다).
              setIntermission(null);
              setSkipped(null);
              break;
            case 'course:intermission':
              setIntermission(event.data as IntermissionData);
              break;
            case 'course:aborted':
              // 첫 게임을 열지 못해 방이 WAITING으로 되돌아갔다 — 대기 화면을 접으면 상위가
              // 대기방을 다시 그리고(스냅샷을 새로 읽는다) 인원을 맞춰 재시작할 수 있다.
              setIntermission(null);
              setActiveSession(null);
              break;
            case 'course:session-skipped':
              setSkipped(event.data as SessionSkippedData);
              break;
            case 'course:finished':
              setFinished(event.data as CourseFinishedData);
              setActiveSession(null);
              setIntermission(null);
              // 지난 코스에서 쌓인 복귀 기록을 비운다 — 이번 결과 화면은 전원이 아직 여기 있다.
              setReturnedParticipantIds(new Set());
              break;
            case 'course:member-returned': {
              // 복귀는 개별 행동이다 — 돌아간 사람이 나일 때만 결과 화면을 접고 대기방으로
              // 전환한다(대기방이 스냅샷을 새로 읽는다). 남이 돌아간 건 내 화면 전환과는
              // 무관하지만, 그 사람 캠은 이제 대기방 쪽에 있으므로 결과 화면에서 지운다
              // (대기방 타일 표시는 useRoomLobby가 따로 갱신한다).
              const data = event.data as MemberReturnedData;
              setReturnedParticipantIds((prev) => {
                if (prev.has(data.participantId)) return prev;
                const next = new Set(prev);
                next.add(data.participantId);
                return next;
              });
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
    intermission,
    returnedParticipantIds,
    skipped,
    clearSkipped: () => setSkipped(null),
  };
}
