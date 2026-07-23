import { Client } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';

export interface GameStartedPayload {
  gameId: number;
  sessionSeq: number;
  totalRounds: number;
}

interface RoomEvent<T> {
  event: string;
  roomId: string;
  data: T;
}

export function useRoomGameStarted(
  roomId: string | null,
  accessToken: string | null,
  onGameStarted: (payload: GameStartedPayload) => void,
) {
  const handlerRef = useRef(onGameStarted);

  useEffect(() => {
    handlerRef.current = onGameStarted;
  }, [onGameStarted]);

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
          const event = JSON.parse(message.body) as RoomEvent<unknown>;
          if (event.event !== 'game:started') return;
          handlerRef.current(event.data as GameStartedPayload);
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);
}
