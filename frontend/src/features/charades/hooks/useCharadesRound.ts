import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  charadesApi,
  CharadesApiError,
  type CharadesStateResponse,
} from '../api/charadesApi';
import { remainingResultHoldMs } from '../lib/resultBannerHold';
import { useCharadesAnswerSound } from './useCharadesAnswerSound';

// 표현자가 정답을 확인하고 있는 시간(라운드 시작 직후 잠깐 "다음 표현자는 OOO입니다" 예고를 보여주는 시간).
const PREVIEW_DURATION_MS = 2200;

export type CharadesPhase = 'preview' | 'playing' | 'correct' | 'timeout' | 'invalidated';

// "이번 턴이 이렇게 끝났다"를 보여주는 구간 — 다음 턴/게임 종료를 이 동안은 붙잡아둔다.
function isResultPhase(phase: CharadesPhase | null): boolean {
  return phase === 'correct' || phase === 'timeout' || phase === 'invalidated';
}

export interface CharadesChatEntry {
  id: string;
  participantId: string;
  nickname: string;
  text: string;
}

interface RoomEvent<T> {
  event: string;
  roomId: string;
  data: T;
}

interface TurnStartedData {
  round: number;
  turn: number;
  totalTurnsInRound: number;
  presenterId: string;
  expiresAt: string;
}

interface RoundStartedData {
  round: number;
  totalRounds: number;
  totalTurnsInRound: number;
}

interface ChatMessageData {
  round: number;
  turn: number;
  participantId: string;
  nickname: string;
  text: string;
}

interface AnswerRevealedData {
  round: number;
  turn: number;
  presenterId: string;
  answererId: string;
}

interface RoundInvalidatedData {
  round: number;
  turn: number;
  presenterId: string;
  reason: string;
}

// charades 전용 게임 진행 상태. 닌자와 달리 백엔드가 진행 전개를 전부 STOMP(/topic/rooms/{roomId})로
// 밀어주기 때문에(GET .../state 폴링 없음) 여기선 순수 이벤트 구독만으로 상태를 구성한다.
export function useCharadesRound(
  roomId: string,
  gameId: number,
  accessToken: string,
  participantId: string | null,
) {
  const [totalRounds, setTotalRounds] = useState<number | null>(null);
  const [round, setRound] = useState<number | null>(null);
  const [turn, setTurn] = useState<number | null>(null);
  const [totalTurnsInRound, setTotalTurnsInRound] = useState<number | null>(null);
  const [presenterId, setPresenterId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [phase, setPhase] = useState<CharadesPhase | null>(null);
  const [myWord, setMyWord] = useState<string | null>(null);
  const [chatLog, setChatLog] = useState<CharadesChatEntry[]>([]);
  const [lastAnswererId, setLastAnswererId] = useState<string | null>(null);
  const [lastInvalidReason, setLastInvalidReason] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [gameEnded, setGameEnded] = useState(false);
  // charades:game-ended가 도착했지만 정답 배너 때문에 아직 반영을 미루고 있는 상태 —
  // 이 동안 배너는 "다음 제시어" 대신 "결과가 공개됩니다"로 안내한다.
  const [gameEndPending, setGameEndPending] = useState(false);
  const playAnswerSound = useCharadesAnswerSound();

  // turn-started 처리를 지연시키기 위한 참조 — phase는 클로저 밖(STOMP 콜백)에서 최신값을 읽어야 해서 ref로 미러링한다.
  const phaseRef = useRef<CharadesPhase | null>(null);
  phaseRef.current = phase;
  const roundRef = useRef<number | null>(null);
  roundRef.current = round;
  const turnRef = useRef<number | null>(null);
  turnRef.current = turn;
  const resultShownAtRef = useRef<number | null>(null);
  const pendingAfterResultRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stateEventVersionRef = useRef(0);
  const syncRequestIdRef = useRef(0);

  const isPresenter = participantId !== null && presenterId === participantId;

  // 턴 결과 배너가 최소 노출 시간을 못 채웠으면 그만큼 기다렸다가 실행한다. 다음 턴 전환(turn-started)과
  // 게임 종료(game-ended) 둘 다 배너를 덮어버리므로 같은 유예를 태운다 — 특히 마지막 턴에서는
  // 결과 이벤트 직후 game-ended가 따라와서, 유예가 없으면 배너가 아예 안 보인다.
  //
  // 정답뿐 아니라 시간 초과·무효도 "이번 턴 결과"를 알려주는 화면이라 똑같이 붙잡는다.
  const runAfterResultBanner = useCallback((apply: () => void) => {
    if (pendingAfterResultRef.current) clearTimeout(pendingAfterResultRef.current);
    const phase = phaseRef.current;
    const remaining = isResultPhase(phase) ? remainingResultHoldMs(resultShownAtRef.current) : 0;
    if (remaining > 0) {
      pendingAfterResultRef.current = setTimeout(apply, remaining);
      return;
    }
    apply();
  }, []);

  const handleTurnStarted = useCallback((data: TurnStartedData) => {
    roundRef.current = data.round;
    turnRef.current = data.turn;
    setRound(data.round);
    setTurn(data.turn);
    setTotalTurnsInRound(data.totalTurnsInRound);
    setPresenterId(data.presenterId);
    setExpiresAt(data.expiresAt);
    setMyWord(null);
    setChatLog([]);
    setLastAnswererId(null);
    setLastInvalidReason(null);
    setGameEnded(false);
    setGameEndPending(false);
    setError(null);
    setPhase('preview');
  }, []);

  const applyStateSnapshotNow = useCallback((state: CharadesStateResponse) => {
    const snapshotRound = state.round || null;
    const snapshotTurn = state.turn || null;
    const turnChanged =
      roundRef.current !== snapshotRound || turnRef.current !== snapshotTurn;

    roundRef.current = snapshotRound;
    turnRef.current = snapshotTurn;
    setTotalRounds(state.totalRounds || null);
    setRound(snapshotRound);
    setTurn(snapshotTurn);
    setTotalTurnsInRound(state.totalTurnsInRound || null);
    setPresenterId(state.presenterId);
    setExpiresAt(state.expiresAt);
    if (turnChanged) {
      setMyWord(null);
      setChatLog([]);
      setLastAnswererId(null);
      setLastInvalidReason(null);
    }

    switch (state.status) {
      case 'PLAYING':
        setGameEnded(false);
        setError(null);
        setPhase('playing');
        break;
      case 'CORRECT':
        setPhase('correct');
        break;
      case 'TIMEOUT':
        setPhase('timeout');
        break;
      case 'INVALIDATED':
        setPhase('invalidated');
        break;
      case 'FINISHED':
        setGameEnded(true);
        setGameEndPending(false);
        setPhase(null);
        break;
      case 'READY':
      default:
        setPhase(null);
        break;
    }
  }, []);

  // 스냅샷도 게임 종료를 담아 올 수 있다(정답 직후 재연결 등) — 이벤트와 같은 유예를 태워서
  // 그 경로로 배너가 잘리지 않게 한다. 배너가 안 떠 있으면 유예 없이 바로 반영된다.
  const applyStateSnapshot = useCallback(
    (state: CharadesStateResponse) => {
      if (state.status === 'FINISHED') {
        setGameEndPending(true);
        runAfterResultBanner(() => applyStateSnapshotNow(state));
        return;
      }
      applyStateSnapshotNow(state);
    },
    [applyStateSnapshotNow, runAfterResultBanner],
  );

  const syncState = useCallback(async () => {
    if (!participantId) return;

    const requestId = ++syncRequestIdRef.current;
    const versionAtRequest = stateEventVersionRef.current;
    try {
      const state = await charadesApi.getState(gameId, accessToken);
      if (syncRequestIdRef.current !== requestId) return;
      if (stateEventVersionRef.current !== versionAtRequest) return;
      applyStateSnapshot(state);
    } catch (err: unknown) {
      if (syncRequestIdRef.current !== requestId) return;
      if (stateEventVersionRef.current !== versionAtRequest) return;
      setError(
        err instanceof CharadesApiError
          ? `게임 상태 조회 실패: ${err.message}`
          : '게임 상태 조회 실패',
      );
    }
  }, [participantId, gameId, accessToken, applyStateSnapshot]);

  useEffect(() => {
    if (!roomId || !accessToken) return;

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
          if (
            event.event === 'charades:turn-started' ||
            event.event === 'charades:answer-revealed' ||
            event.event === 'charades:round-timeout' ||
            event.event === 'charades:round-invalidated' ||
            event.event === 'charades:game-ended'
          ) {
            stateEventVersionRef.current += 1;
          }
          switch (event.event) {
            case 'charades:round-started': {
              const data = event.data as RoundStartedData;
              setTotalRounds(data.totalRounds);
              break;
            }
            case 'charades:turn-started': {
              const data = event.data as TurnStartedData;
              runAfterResultBanner(() => handleTurnStarted(data));
              break;
            }
            case 'chat:message-received': {
              const data = event.data as ChatMessageData;
              setChatLog((prev) => [
                ...prev,
                {
                  id: crypto.randomUUID(),
                  participantId: data.participantId,
                  nickname: data.nickname,
                  text: data.text,
                },
              ]);
              break;
            }
            case 'charades:answer-revealed': {
              const data = event.data as AnswerRevealedData;
              setLastAnswererId(data.answererId);
              resultShownAtRef.current = Date.now();
              playAnswerSound(true);
              setPhase('correct');
              break;
            }
            case 'charades:round-timeout': {
              // payload에 제시어가 없다(round/turn뿐) — 정답 공개는 표현자 본인의 myWord로만 가능하다.
              resultShownAtRef.current = Date.now();
              setPhase('timeout');
              break;
            }
            case 'charades:round-invalidated': {
              const data = event.data as RoundInvalidatedData;
              setLastInvalidReason(data.reason);
              resultShownAtRef.current = Date.now();
              setPhase('invalidated');
              break;
            }
            case 'charades:game-ended': {
              // 마지막 턴의 정답이면 answer-revealed 바로 뒤에 이 이벤트가 붙어 온다 — 배너를
              // 다 보여준 뒤에 종료를 반영해야 제시어가 공개된다(중간 턴의 turn-started와 같은 유예).
              setGameEndPending(true);
              runAfterResultBanner(() => {
                setGameEnded(true);
                setGameEndPending(false);
                setPhase(null);
              });
              break;
            }
            default:
              break;
          }
        });
        void syncState();
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
      if (pendingAfterResultRef.current) clearTimeout(pendingAfterResultRef.current);
    };
  }, [roomId, accessToken, handleTurnStarted, syncState, playAnswerSound, runAfterResultBanner]);

  useEffect(() => {
    if (!participantId) return;
    void syncState();
  }, [participantId, syncState]);

  // 라운드/턴이 바뀌면 잠깐 "다음 표현자는 OOO입니다" 예고를 보여준 뒤 실제 진행 화면으로 넘어간다.
  useEffect(() => {
    if (phase !== 'preview') return;
    const timer = setTimeout(() => setPhase('playing'), PREVIEW_DURATION_MS);
    return () => clearTimeout(timer);
  }, [phase, round, turn]);

  // 표현자 본인이면 진행 화면으로 넘어가는 시점에 제시어를 받아온다.
  useEffect(() => {
    if (phase !== 'playing' || !isPresenter) return;
    let cancelled = false;
    charadesApi
      .getCurrentWord(gameId, accessToken)
      .then((res) => {
        if (!cancelled) setMyWord(res.word);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof CharadesApiError ? `제시어 조회 실패: ${err.message}` : '제시어 조회 실패');
      });
    return () => {
      cancelled = true;
    };
  }, [phase, isPresenter, gameId, accessToken]);

  const submitGuess = useCallback(
    async (text: string) => {
      if (phase !== 'playing' || isPresenter) return;
      try {
        const result = await charadesApi.submitGuess(gameId, text, accessToken);
        if (!result.correct) playAnswerSound(false);
      } catch (err) {
        setError(err instanceof CharadesApiError ? err.message : '정답 제출 실패');
      }
    },
    [phase, isPresenter, gameId, accessToken, playAnswerSound],
  );

  const timeLeftSeconds = useTimeLeft(expiresAt, phase === 'playing');

  return {
    totalRounds,
    round,
    turn,
    totalTurnsInRound,
    presenterId,
    isPresenter,
    phase,
    expiresAt,
    myWord,
    chatLog,
    lastAnswererId,
    lastInvalidReason,
    timeLeftSeconds,
    gameEnded,
    gameEndPending,
    error,
    submitGuess,
  };
}

// expiresAt(서버 절대시각)까지 남은 초를 1초 간격으로 갱신 — 폴링 없이도 라운드 타이머를 보여줄 수 있다.
function useTimeLeft(expiresAt: string | null, active: boolean): number | null {
  const [left, setLeft] = useState<number | null>(null);
  const expiresAtMs = useMemo(() => (expiresAt ? new Date(expiresAt).getTime() : null), [expiresAt]);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!active || expiresAtMs === null) {
      setLeft(null);
      return;
    }
    const tick = () => setLeft(Math.max(0, Math.round((expiresAtMs - Date.now()) / 1000)));
    tick();
    intervalRef.current = setInterval(tick, 1000);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [active, expiresAtMs]);

  return left;
}
