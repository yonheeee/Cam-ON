import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  charadesApi,
  CharadesApiError,
  type CharadesStateResponse,
} from '../api/charadesApi';
import { useCharadesAnswerSound } from './useCharadesAnswerSound';

// 표현자가 정답을 확인하고 있는 시간(라운드 시작 직후 잠깐 "다음 표현자는 OOO입니다" 예고를 보여주는 시간).
const PREVIEW_DURATION_MS = 2200;

// "정답!" 배너를 최소 이만큼 보여준다. 백엔드가 정답 처리 직후 딜레이 없이 바로 다음 턴을 열어버려서
// (CharadesGameService.submitGuess → advanceAfterTerminalTurn 동기 호출) turn-started가 거의 즉시
// 도착한다 — 그걸 이 시간만큼 붙잡아뒀다가 반영해서 배너가 잘리지 않게 한다.
const CORRECT_BANNER_HOLD_MS = 5000;

export type CharadesPhase = 'preview' | 'playing' | 'correct' | 'timeout' | 'invalidated';

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
  const playAnswerSound = useCharadesAnswerSound();

  // turn-started 처리를 지연시키기 위한 참조 — phase는 클로저 밖(STOMP 콜백)에서 최신값을 읽어야 해서 ref로 미러링한다.
  const phaseRef = useRef<CharadesPhase | null>(null);
  phaseRef.current = phase;
  const roundRef = useRef<number | null>(null);
  roundRef.current = round;
  const turnRef = useRef<number | null>(null);
  turnRef.current = turn;
  const correctShownAtRef = useRef<number | null>(null);
  const pendingTurnTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stateEventVersionRef = useRef(0);
  const syncRequestIdRef = useRef(0);

  const isPresenter = participantId !== null && presenterId === participantId;

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
    setError(null);
    setPhase('preview');
  }, []);

  const applyStateSnapshot = useCallback((state: CharadesStateResponse) => {
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
        setPhase(null);
        break;
      case 'READY':
      default:
        setPhase(null);
        break;
    }
  }, []);

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
              if (pendingTurnTimerRef.current) clearTimeout(pendingTurnTimerRef.current);
              if (phaseRef.current === 'correct' && correctShownAtRef.current !== null) {
                const remaining = CORRECT_BANNER_HOLD_MS - (Date.now() - correctShownAtRef.current);
                if (remaining > 0) {
                  pendingTurnTimerRef.current = setTimeout(() => handleTurnStarted(data), remaining);
                  break;
                }
              }
              handleTurnStarted(data);
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
              correctShownAtRef.current = Date.now();
              playAnswerSound(true);
              setPhase('correct');
              break;
            }
            case 'charades:round-timeout': {
              // payload에서 쓸 값이 없다 — 시간 초과 사실만 화면에 반영하면 된다.
              setPhase('timeout');
              break;
            }
            case 'charades:round-invalidated': {
              const data = event.data as RoundInvalidatedData;
              setLastInvalidReason(data.reason);
              setPhase('invalidated');
              break;
            }
            case 'charades:game-ended': {
              setGameEnded(true);
              setPhase(null);
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
      if (pendingTurnTimerRef.current) clearTimeout(pendingTurnTimerRef.current);
    };
  }, [roomId, accessToken, handleTurnStarted, syncState, playAnswerSound]);

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
