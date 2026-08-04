import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useRef, useState } from 'react';

export interface NinjaConnectionNotice {
  participantId: string | null;
  kind: 'DISCONNECTED' | 'RECONNECTED' | 'TIMED_OUT';
}

interface MemberConnectionPayload {
  participantId: string;
  connectionStatus: 'CONNECTED' | 'DISCONNECTED';
}

interface MemberLeftPayload {
  participantId: string;
  reason: string;
}

// 서버가 /topic/rooms/{roomId}로 미는 ninja:* 이벤트(round-started/attack-won/attack-resolved/
// round-timeout/game-ended)를 구독해 payload째로 onNinjaEvent에 넘긴다. useNinjaRound가 이걸
// 리듀서처럼 소비해 상태를 증분 갱신한다 — 폴링 없음.
//
// onConnected는 STOMP가 (재)연결될 때마다 불린다. 이벤트는 "발생 순간 접속해 있던 사람"에게만
// 가므로, 새로고침/끊김 복귀로 이벤트 공백이 생긴 클라이언트는 이 콜백에서 GET .../state로
// 스냅샷을 한 번 받아 따라잡는다(그 이후는 다시 이벤트로만).
export function useNinjaRealtime(
  roomId: string | null,
  accessToken: string | null,
  onNinjaEvent: (eventName: string, data: unknown) => void,
  onConnected: () => void,
) {
  const handlerRef = useRef(onNinjaEvent);
  const connectedRef = useRef(onConnected);
  const everConnectedRef = useRef(false);
  const reconnectingRef = useRef(false);
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const selfTimeoutTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [selfReconnecting, setSelfReconnecting] = useState(false);
  const [selfConnectionTimedOut, setSelfConnectionTimedOut] = useState(false);
  const [disconnectedParticipantIds, setDisconnectedParticipantIds] = useState<string[]>([]);
  const [connectionNotice, setConnectionNotice] = useState<NinjaConnectionNotice | null>(null);

  const showNotice = useCallback((notice: NinjaConnectionNotice) => {
    if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current);
    setConnectionNotice(notice);
    noticeTimerRef.current = setTimeout(() => setConnectionNotice(null), 4000);
  }, []);
  useEffect(() => {
    handlerRef.current = onNinjaEvent;
    connectedRef.current = onConnected;
  }, [onNinjaEvent, onConnected]);

  useEffect(() => {
    if (!roomId || !accessToken) return;

    const defaultProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const baseUrl =
      import.meta.env.VITE_WS_BASE_URL ?? `${defaultProtocol}//${window.location.hostname}:8080`;
    const client = new Client({
      brokerURL: `${baseUrl}/ws/rooms/${roomId}`,
      connectHeaders: {
        Authorization: `Bearer ${accessToken}`,
      },
      reconnectDelay: 3000,
      // STOMP는 인증 실패에도 reconnectDelay로 재연결을 계속 시도한다 — 죽은 토큰으로는
      // 영원히 실패하므로, 세션을 정리하고 첫 화면으로 되돌려 루프를 끊는다.
      onStompError: () => handleExpiredSession(),
      onConnect: () => {
        const reconnected = reconnectingRef.current;
        everConnectedRef.current = true;
        reconnectingRef.current = false;
        if (selfTimeoutTimerRef.current) clearTimeout(selfTimeoutTimerRef.current);
        setSelfReconnecting(false);
        setSelfConnectionTimedOut(false);
        if (reconnected) showNotice({ participantId: null, kind: 'RECONNECTED' });
        client.subscribe(`/topic/rooms/${roomId}`, (message) => {
          const event = JSON.parse(message.body) as { event?: string; data?: unknown };
          if (typeof event.event === 'string' && event.event.startsWith('ninja:')) {
            handlerRef.current(event.event, event.data);
            return;
          }

          if (event.event === 'member:connection-changed') {
            const data = event.data as MemberConnectionPayload;
            if (data.connectionStatus === 'DISCONNECTED') {
              setDisconnectedParticipantIds((prev) =>
                prev.includes(data.participantId) ? prev : [...prev, data.participantId],
              );
              showNotice({ participantId: data.participantId, kind: 'DISCONNECTED' });
            } else {
              setDisconnectedParticipantIds((prev) =>
                prev.filter((participantId) => participantId !== data.participantId),
              );
              showNotice({ participantId: data.participantId, kind: 'RECONNECTED' });
            }
            return;
          }

          if (event.event === 'member:left') {
            const data = event.data as MemberLeftPayload;
            setDisconnectedParticipantIds((prev) =>
              prev.filter((participantId) => participantId !== data.participantId),
            );
            if (data.reason === 'TIMEOUT') {
              showNotice({ participantId: data.participantId, kind: 'TIMED_OUT' });
            }
          }
        });
        // 구독을 걸어둔 "뒤에" 동기화해야 스냅샷과 다음 이벤트 사이에 빈틈이 없다.
        connectedRef.current();
      },
      onWebSocketClose: () => {
        if (!everConnectedRef.current) return;
        reconnectingRef.current = true;
        setSelfReconnecting(true);
        if (selfTimeoutTimerRef.current) clearTimeout(selfTimeoutTimerRef.current);
        selfTimeoutTimerRef.current = setTimeout(() => setSelfConnectionTimedOut(true), 15_100);
      },
    });

    client.activate();
    return () => {
      if (noticeTimerRef.current) clearTimeout(noticeTimerRef.current);
      if (selfTimeoutTimerRef.current) clearTimeout(selfTimeoutTimerRef.current);
      void client.deactivate();
    };
  }, [roomId, accessToken, showNotice]);

  return {
    selfReconnecting,
    selfConnectionTimedOut,
    disconnectedParticipantIds,
    connectionNotice,
  };
}
