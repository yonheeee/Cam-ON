import { Client } from '@stomp/stompjs';
import { useEffect } from 'react';

// 백엔드는 STOMP CONNECT 시점에 session:{participantId}:alive(TTL 15초) 가드를 걸고, 이후
// 하트비트로 갱신되지 않으면 ~15초 뒤 참가자를 방에서 제거한다. 혼자 있던 방장이 제거되면
// 방 자체가 Redis에서 삭제돼 초대 코드가 "존재하지 않는 방"이 된다. 그래서 방에 머무는 내내
// 하트비트를 주기적으로 보내 살아있음을 유지해야 한다(대기방/게임 상관없이 항상).
const HEARTBEAT_INTERVAL_MS = 5000;

export function useRoomHeartbeat(roomId: string, accessToken: string) {
  useEffect(() => {
    const defaultProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const baseUrl =
      import.meta.env.VITE_WS_BASE_URL ?? `${defaultProtocol}//${window.location.hostname}:8080`;

    let timer: ReturnType<typeof setInterval> | undefined;
    const client = new Client({
      brokerURL: `${baseUrl}/ws/rooms/${roomId}`,
      connectHeaders: {
        Authorization: `Bearer ${accessToken}`,
      },
      reconnectDelay: 3000,
      onConnect: () => {
        const beat = () =>
          client.publish({ destination: `/app/rooms/${roomId}/heartbeat`, body: '' });
        beat(); // CONNECT 직후 바로 한 번(가드가 갱신되도록)
        timer = setInterval(beat, HEARTBEAT_INTERVAL_MS);
      },
      onDisconnect: () => {
        if (timer) clearInterval(timer);
      },
    });

    client.activate();
    return () => {
      if (timer) clearInterval(timer);
      void client.deactivate();
    };
  }, [roomId, accessToken]);
}
