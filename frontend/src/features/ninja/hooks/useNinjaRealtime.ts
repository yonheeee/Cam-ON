import { Client } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';

// 라운드 진행/공격 resolve/인터미션 전환을 실시간으로 반영하기 위한 구독. 서버가 /topic/rooms/{roomId}로
// 미는 ninja:* 이벤트(round-started/attack-won/attack-resolved/round-timeout/game-ended)를 받으면
// 곧바로 onNinjaEvent를 호출한다 — useNinjaRound는 이 콜백에서 상태를 즉시 다시 폴링해서,
// 최대 폴링 주기(1.5초)를 기다리지 않고 반응한다.
//
// 전환의 "기준"은 어디까지나 서버가 내려준 상태(GET .../state)와 그 안의 서버 기준 시각이다. 이
// 구독은 "언제 다시 읽을지"를 앞당기는 트리거일 뿐이라, WS를 놓쳐도(끊김/재접속) 폴링 폴백으로
// 동일한 상태에 수렴한다. 단일 소스는 그대로 getState 하나.
export function useNinjaRealtime(
  roomId: string | null,
  accessToken: string | null,
  onNinjaEvent: (eventName: string) => void,
) {
  const handlerRef = useRef(onNinjaEvent);
  useEffect(() => {
    handlerRef.current = onNinjaEvent;
  }, [onNinjaEvent]);

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
          const event = JSON.parse(message.body) as { event?: string };
          if (typeof event.event === 'string' && event.event.startsWith('ninja:')) {
            handlerRef.current(event.event);
          }
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);
}
