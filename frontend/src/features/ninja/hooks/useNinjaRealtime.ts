import { Client } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';

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
      onConnect: () => {
        client.subscribe(`/topic/rooms/${roomId}`, (message) => {
          const event = JSON.parse(message.body) as { event?: string; data?: unknown };
          if (typeof event.event === 'string' && event.event.startsWith('ninja:')) {
            handlerRef.current(event.event, event.data);
          }
        });
        // 구독을 걸어둔 "뒤에" 동기화해야 스냅샷과 다음 이벤트 사이에 빈틈이 없다.
        connectedRef.current();
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);
}
