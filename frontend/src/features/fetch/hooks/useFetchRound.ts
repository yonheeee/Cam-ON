import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchGameApi, FetchGameApiError } from '../api/fetchGameApi';
import type { FetchObjectStateResponse } from '../api/fetchGameApi';
import { COUNTDOWN_MS, type FetchGameState } from '../types/fetchGame';

// 물건 가져오기의 백엔드 주도 진행 훅.
//
// 서버가 라운드를 전부 주도한다: round:start(제시어 포함, 3초 카운트다운+40초) →
// 전원 제출 또는 타임아웃 시 round:end → 즉시 다음 round:start → 마지막 라운드 뒤 game:end.
// 클라이언트는 이벤트를 소비해 화면 상태를 만들고, 인식 성공 시 POST submissions만 한다.
//
// 구독 직후 GET /state를 함께 호출해 첫 round:start 유실과 게임 중 새로고침을 복구한다.
// 구독을 먼저 열고 이벤트 버전을 비교하므로 조회 중 발생한 새 이벤트가 과거 상태에 덮이지 않는다.

interface RoundStartData {
  round: number;
  totalRounds: number;
  target: string;
  startedAt: number; // epoch ms — 이 시각 기준 3초 카운트다운 + 40초 플레이
}

interface RoundSuccessData {
  round: number;
  participantId: string;
  rank: number;
  score: number;
  submittedAt: number;
  /** 첫 정답으로 라운드 마감이 단축됐을 때의 새 마감(epoch ms). 아니면 null */
  roundDeadlineAt: number | null;
}

interface ScoreEntryData {
  participantId: string;
  score: number;
  rank: number;
}

interface RoundEndData {
  round: number;
  totalRounds: number;
  endedAt: number;
  scores: ScoreEntryData[];
  /** 스킵 투표 가결로 끝난 라운드 — 결과 화면에서 "시간 초과"와 구분 */
  skipped: boolean;
}

interface SkipVoteData {
  round: number;
  participantId: string;
  votes: number;
  required: number;
}

interface GameEndData {
  scores: ScoreEntryData[];
}

const initialState: FetchGameState = {
  phase: 'idle',
  round: 0,
  totalRounds: 0,
  target: null,
  startedAt: 0,
  successes: [],
  totals: {},
  skipVotes: [],
  skipRequired: 0,
  roundSkipped: false,
};

export function useFetchRound(
  roomId: string,
  gameId: number,
  accessToken: string,
  /** participantId → 닉네임 (화면 표기는 전부 닉네임. 방 스냅샷 기준) */
  resolveNickname: (participantId: string) => string,
) {
  const [state, setState] = useState<FetchGameState>(initialState);
  const [submissionError, setSubmissionError] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;
  const resolveRef = useRef(resolveNickname);
  resolveRef.current = resolveNickname;
  // round:success에서 즉시 올린 점수를 round:end의 확정 점수와 대조해 차이만 반영한다.
  // 이 기록이 없으면 즉시 반영한 점수가 round:end에서 한 번 더 더해진다.
  const provisionalScoresRef = useRef(new Map<string, number>());
  const submittedRoundRef = useRef(0);
  const activeRoundRef = useRef(0);
  // 상태 조회가 진행되는 동안 더 최신 STOMP 이벤트가 오면, 늦게 도착한 조회 응답이 화면을
  // 과거로 되돌리지 않도록 요청 시작 시점의 이벤트 버전을 비교한다.
  const stateEventVersionRef = useRef(0);

  const applySnapshot = useCallback((snapshot: FetchObjectStateResponse) => {
    const successes = snapshot.successes.map((success) => ({
      participantId: success.participantId,
      nickname: resolveRef.current(success.participantId),
      rank: success.rank,
      score: success.score,
      elapsedMs: Math.max(
        0,
        success.submittedAt - (snapshot.startedAt + COUNTDOWN_MS),
      ),
    }));
    const totals: Record<string, number> = {};
    for (const entry of snapshot.totals) {
      totals[resolveRef.current(entry.participantId)] = entry.score;
    }
    provisionalScoresRef.current = new Map(
      snapshot.successes.map((success) => [success.participantId, success.score]),
    );
    activeRoundRef.current = snapshot.round;
    submittedRoundRef.current = 0;
    setState({
      phase: snapshot.status === 'PLAYING' ? 'playing' : 'roundResult',
      round: snapshot.round,
      totalRounds: snapshot.totalRounds,
      target: snapshot.target,
      startedAt: snapshot.startedAt,
      deadlineAt: snapshot.deadlineAt,
      successes,
      totals,
      skipVotes: snapshot.skipVotes ?? [],
      // 스냅샷엔 분모가 없다(투표 이벤트가 실어 줌) — 0이면 화면이 "n/전체 인원"으로 폴백.
      skipRequired: 0,
      roundSkipped: false,
    });
    setSyncError(null);
  }, []);

  const syncState = useCallback(async () => {
    const versionAtRequest = stateEventVersionRef.current;
    try {
      const snapshot = await fetchGameApi.getState(gameId, accessToken);
      if (stateEventVersionRef.current !== versionAtRequest) return;
      applySnapshot(snapshot);
    } catch (err) {
      setSyncError(
        err instanceof FetchGameApiError
          ? err.message
          : '현재 물건 가져오기 상태를 불러오지 못했습니다.',
      );
    }
  }, [gameId, accessToken, applySnapshot]);

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
          const event = JSON.parse(message.body) as { event?: string; data?: unknown };
          switch (event.event) {
            case 'round:start': {
              stateEventVersionRef.current += 1;
              const data = event.data as RoundStartData;
              activeRoundRef.current = data.round;
              provisionalScoresRef.current = new Map();
              submittedRoundRef.current = 0;
              setSubmissionError(null);
              setState((prev) => ({
                ...prev,
                phase: 'playing',
                round: data.round,
                totalRounds: data.totalRounds,
                target: data.target,
                startedAt: data.startedAt,
                deadlineAt: null,
                successes: [],
                totals: data.round === 1 ? {} : prev.totals,
                skipVotes: [],
                skipRequired: 0,
                roundSkipped: false,
              }));
              break;
            }
            case 'round:skip-voted': {
              const data = event.data as SkipVoteData;
              if (activeRoundRef.current !== data.round) break;
              setState((prev) => {
                if (prev.phase !== 'playing' || prev.round !== data.round) return prev;
                return {
                  ...prev,
                  skipVotes: prev.skipVotes.includes(data.participantId)
                    ? prev.skipVotes
                    : [...prev.skipVotes, data.participantId],
                  skipRequired: data.required,
                };
              });
              break;
            }
            case 'round:success': {
              // 도착 순서와 제출 시각은 서버 원본이며, 확정 라운드 점수표는 round:end가 준다.
              // 첫 정답이면 서버가 마감을 그레이스(10초)로 단축한 새 마감(roundDeadlineAt)을
              // 함께 실어 준다 — 타이머에도 즉시 반영한다.
              const data = event.data as RoundSuccessData;
              // 첫 round:start를 놓쳐 아직 복구 스냅샷을 기다리는 중이면 이 이벤트만으로는
              // target/startedAt을 알 수 없다. 조회 응답을 무효화하지 않고 스냅샷에 포함된
              // 성공 목록으로 복구하게 둔다.
              if (activeRoundRef.current !== data.round) break;
              stateEventVersionRef.current += 1;
              setState((prev) => {
                if (prev.phase !== 'playing') return prev;
                if (data.round !== prev.round) return prev;
                const deadlineAt = data.roundDeadlineAt ?? prev.deadlineAt ?? null;
                const nickname = resolveRef.current(data.participantId);
                if (prev.successes.some((s) => s.participantId === data.participantId)) {
                  return { ...prev, deadlineAt };
                }
                const elapsedMs = Math.max(
                  0,
                  data.submittedAt - (prev.startedAt + COUNTDOWN_MS),
                );
                provisionalScoresRef.current.set(data.participantId, data.score);
                return {
                  ...prev,
                  deadlineAt,
                  successes: [
                    ...prev.successes,
                    {
                      participantId: data.participantId,
                      nickname,
                      rank: data.rank,
                      score: data.score,
                      elapsedMs,
                    },
                  ],
                  // 성공 이벤트에 이미 서버 확정 점수가 있으므로 라운드 종료까지 기다리지 않고
                  // 모든 참가자 화면의 타일 점수에 즉시 보여준다.
                  totals: {
                    ...prev.totals,
                    [nickname]: (prev.totals[nickname] ?? 0) + data.score,
                  },
                };
              });
              break;
            }
            case 'round:end': {
              const data = event.data as RoundEndData;
              if (activeRoundRef.current !== data.round) break;
              stateEventVersionRef.current += 1;
              setState((prev) => {
                // 라운드 확정 점수를 누적 — 미제출자 0점도 표에 포함돼 오므로 그대로 더한다.
                const totals = { ...prev.totals };
                for (const entry of data.scores) {
                  const nickname = resolveRef.current(entry.participantId);
                  const provisionalScore =
                    provisionalScoresRef.current.get(entry.participantId) ?? 0;
                  totals[nickname] =
                    (totals[nickname] ?? 0) + entry.score - provisionalScore;
                }
                provisionalScoresRef.current = new Map();
                return { ...prev, phase: 'roundResult', totals, roundSkipped: data.skipped };
              });
              break;
            }
            case 'game:end': {
              stateEventVersionRef.current += 1;
              const data = event.data as GameEndData;
              setState((prev) => {
                // 세션 누적 총점(서버 원본)으로 덮어써서 라운드별 누적 오차를 제거한다.
                const totals: Record<string, number> = {};
                for (const entry of data.scores) {
                  totals[resolveRef.current(entry.participantId)] = entry.score;
                }
                return { ...prev, phase: 'ended', totals };
              });
              break;
            }
          }
        });
        // 구독을 먼저 연 뒤 Redis 상태를 조회한다. 이 순서와 이벤트 버전 비교를 함께 써야
        // 조회 중 발생한 round:start/success를 놓치거나 과거 스냅샷으로 덮어쓰지 않는다.
        void syncState();
      },
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken, syncState]);

  // 라운드당 1회만 제출. 클라이언트 시계가 서버보다 빠르면 화면의 카운트다운은 끝났지만
  // 서버는 아직 제출을 열지 않은 짧은 구간이 생길 수 있다. 이 경우에는 인식을 다시 허용해
  // 다음 프레임에서 재시도하고, 중복/마감처럼 이미 결과가 정해진 오류만 종료 처리한다.
  const submit = useCallback(async (
    confidence?: number,
    targetScore?: number,
  ): Promise<boolean> => {
    const round = stateRef.current.round;
    if (round === 0) return false;
    if (submittedRoundRef.current === round) return true;
    submittedRoundRef.current = round;
    setSubmissionError(null);
    try {
      await fetchGameApi.submit(
        gameId,
        round,
        accessToken,
        confidence,
        targetScore,
      );
      // 성공 반영은 서버가 곧바로 브로드캐스트하는 round:success가 담당한다.
      return true;
    } catch (err) {
      if (
        err instanceof FetchGameApiError &&
        err.code === 'FETCH_OBJECT_COUNTDOWN_ACTIVE'
      ) {
        submittedRoundRef.current = 0;
        return false;
      }
      if (err instanceof FetchGameApiError && err.code) {
        setSubmissionError(err.message);
        // 중복 제출이면 성공 이벤트가 이미 발행된 상태다. 그 외 마감·세션 오류도 같은
        // 라운드에서 무한 재요청하지 않고 서버의 다음 이벤트를 기다린다.
        return true;
      }
      // 네트워크 오류 등은 같은 라운드 재시도 허용하고 사용자에게 원인을 보여준다.
      submittedRoundRef.current = 0;
      setSubmissionError(err instanceof Error ? err.message : '성공 제출에 실패했습니다.');
      return false;
    }
  }, [gameId, accessToken]);

  // 스킵 투표. 카운터 반영은 서버 브로드캐스트(round:skip-voted)가 담당하므로 여기선 호출만.
  // 실패는 두 부류로 나눈다: 성공자 등장 직후의 경합(SKIP_UNAVAILABLE)·이미 닫힌 라운드는
  // 곧 도착할 이벤트가 화면을 정리하므로 조용히 무시하고, 그 외(네트워크 등)만 에러로 알린다.
  const voteSkip = useCallback(async () => {
    if (stateRef.current.phase !== 'playing') return;
    try {
      await fetchGameApi.voteSkip(gameId, accessToken);
    } catch (err) {
      if (
        err instanceof FetchGameApiError &&
        (err.code === 'FETCH_OBJECT_SKIP_UNAVAILABLE' ||
          err.code === 'FETCH_OBJECT_ROUND_CLOSED')
      ) {
        return;
      }
      setSubmissionError(
        err instanceof Error ? err.message : '스킵 투표에 실패했습니다.',
      );
    }
  }, [gameId, accessToken]);

  return { state, submit, voteSkip, submissionError, syncError };
}
