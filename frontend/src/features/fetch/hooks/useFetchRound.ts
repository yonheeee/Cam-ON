import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchGameApi, FetchGameApiError } from '../api/fetchGameApi';
import { COUNTDOWN_MS, type FetchGameState } from './useFetchGame';

// 물건 가져오기의 "백엔드 주도" 진행 훅 — 코스가 연 세션용. (useFetchGame은 /dev/fetch 전용
// 데이터채널 mock이고, 실제 게임은 이 훅이 담당한다.)
//
// 서버가 라운드를 전부 주도한다: round:start(제시어 포함, 3초 카운트다운+20초) →
// 전원 제출 또는 타임아웃 시 round:end → 즉시 다음 round:start → 마지막 라운드 뒤 game:end.
// 클라이언트는 이벤트를 소비해 화면 상태를 만들고, 인식 성공 시 POST submissions만 한다.
//
// 세 게임 공통 규칙: 구독 직후와 재연결 직후에 GET .../state로 스냅샷을 한 번 읽고, 그 뒤로는
// 이벤트로만 증분 갱신한다(폴링 없음). 이게 없으면 게임 시작 직후처럼 "구독을 거는 사이에
// 지나간 이벤트"를 영영 못 받아서 다음 라운드까지 빈 화면으로 기다리게 된다.
// [백엔드 미구현] 물건 가져오기의 state 엔드포인트는 아직 없다 — 생기기 전까지 조회는 조용히
// 실패하고 예전처럼 이벤트만으로 진행한다(그동안은 위 증상이 남는다).

interface RoundStartData {
  round: number;
  totalRounds: number;
  target: string;
  startedAt: number; // epoch ms — 이 시각 기준 3초 카운트다운 + 20초 플레이
}

interface RoundSuccessData {
  participantId: string;
  rank: number;
  score: number;
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
}

interface GameEndData {
  scores: ScoreEntryData[];
}

const initialState: FetchGameState = {
  phase: 'idle',
  round: 0,
  totalRounds: 0,
  target: null,
  hostNickname: null, // 서버 주도라 진행권 개념이 없다
  startedAt: 0,
  successes: [],
  totals: {},
};

export function useFetchRound(
  roomId: string,
  gameId: number,
  accessToken: string,
  /** participantId → 닉네임 (화면 표기는 전부 닉네임. 방 스냅샷 기준) */
  resolveNickname: (participantId: string) => string,
) {
  const [state, setState] = useState<FetchGameState>(initialState);
  const stateRef = useRef(state);
  stateRef.current = state;
  const resolveRef = useRef(resolveNickname);
  resolveRef.current = resolveNickname;

  // 스냅샷 동기화 — 닌자(useNinjaRealtime.onConnected)·몸으로말해요와 같은 역할이다.
  // 이벤트는 "발생 순간 접속해 있던 사람"에게만 가므로, 구독을 건 직후 한 번 읽어 그 사이
  // 놓친 라운드(특히 game:started 바로 뒤에 나가는 첫 round:start)를 따라잡는다.
  const syncState = useCallback(async () => {
    try {
      const snapshot = await fetchGameApi.getState(gameId, accessToken);
      setState((prev) => ({
        ...prev,
        phase:
          snapshot.status === 'PLAYING'
            ? 'playing'
            : snapshot.status === 'ROUND_ENDED'
              ? 'roundResult'
              : snapshot.status === 'FINISHED'
                ? 'ended'
                : 'idle',
        round: snapshot.round,
        totalRounds: snapshot.totalRounds,
        target: snapshot.target,
        startedAt: snapshot.startedAt ?? 0,
        deadlineAt: null,
        // 도착 순서만 알 수 있다 — 경과 시간은 이벤트를 받은 사람만 아는 값이라 0으로 둔다.
        successes: snapshot.successes.map((entry) => ({
          nickname: resolveRef.current(entry.participantId),
          elapsedMs: 0,
        })),
        totals: Object.fromEntries(
          snapshot.totals.map((entry) => [resolveRef.current(entry.participantId), entry.score]),
        ),
      }));
    } catch {
      // 조회 실패는 무시 — 엔드포인트가 아직 없는 동안에도 이벤트만으로 진행은 된다.
    }
  }, [gameId, accessToken]);

  // 마운트 직후 1회 (STOMP 연결이 늦거나 실패해도 현재 스냅샷은 그린다)
  useEffect(() => {
    void syncState();
  }, [syncState]);

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
              const data = event.data as RoundStartData;
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
              }));
              break;
            }
            case 'round:success': {
              // 도착 순서 잠정치 — 확정 점수표는 round:end가 준다. elapsedMs는 서버가 안 주므로
              // 이벤트 수신 시각으로 근사한다(표시용). 첫 정답이면 서버가 마감을 그레이스(5초)로
              // 단축한 새 마감(roundDeadlineAt)을 함께 실어 준다 — 타이머에 즉시 반영.
              const data = event.data as RoundSuccessData;
              setState((prev) => {
                if (prev.phase !== 'playing') return prev;
                const deadlineAt = data.roundDeadlineAt ?? prev.deadlineAt ?? null;
                const nickname = resolveRef.current(data.participantId);
                if (prev.successes.some((s) => s.nickname === nickname)) {
                  return { ...prev, deadlineAt };
                }
                const elapsedMs = Math.max(0, Date.now() - (prev.startedAt + COUNTDOWN_MS));
                return {
                  ...prev,
                  deadlineAt,
                  successes: [...prev.successes, { nickname, elapsedMs }],
                };
              });
              break;
            }
            case 'round:end': {
              const data = event.data as RoundEndData;
              setState((prev) => {
                // 라운드 확정 점수를 누적 — 미제출자 0점도 표에 포함돼 오므로 그대로 더한다.
                const totals = { ...prev.totals };
                for (const entry of data.scores) {
                  const nickname = resolveRef.current(entry.participantId);
                  totals[nickname] = (totals[nickname] ?? 0) + entry.score;
                }
                return { ...prev, phase: 'roundResult', totals };
              });
              break;
            }
            case 'game:end': {
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
        // 구독을 걸어둔 "뒤에" 동기화해야 스냅샷과 다음 이벤트 사이에 빈틈이 없다
        // (재연결 때마다 불린다 — 끊긴 동안 지나간 라운드를 여기서 따라잡는다).
        void syncState();
      },
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken, syncState]);

  // 라운드당 1회만 제출. 409(중복/마감 직후 경합/카운트다운)는 정상 경합이라 조용히 넘어간다.
  const submittedRoundRef = useRef(0);
  const submit = useCallback(async () => {
    const round = stateRef.current.round;
    if (round === 0 || submittedRoundRef.current === round) return;
    submittedRoundRef.current = round;
    try {
      await fetchGameApi.submit(gameId, round, accessToken);
      // 성공 반영은 서버가 곧바로 브로드캐스트하는 round:success가 담당한다.
    } catch (err) {
      if (err instanceof FetchGameApiError && err.code) return;
      // 네트워크 오류 등은 같은 라운드 재시도 허용
      submittedRoundRef.current = 0;
    }
  }, [gameId, accessToken]);

  return { state, submit };
}
