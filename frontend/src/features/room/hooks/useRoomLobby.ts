import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useState } from 'react';
import { roomApi, type ParticipantResponse, type RoomSnapshotResponse } from '../api/roomApi';

// useRoomGameStarted.ts와 동일한 연결 패턴(brokerURL, connectHeaders, reconnectDelay) —
// 백엔드가 /topic/rooms/{roomId} 하나에 이벤트 종류(event 필드)만 다르게 실어 보내므로
// 대기방 전용 구독을 별도 훅으로 분리했다.
interface RoomEvent<T> {
  event: string;
  roomId: string;
  data: T;
}

interface MemberJoinedPayload {
  participantId: string;
  nickname: string;
}

interface MemberLeftPayload {
  participantId: string;
  reason: string;
  newHostParticipantId: string | null;
}

interface HostChangedPayload {
  previousHostParticipantId: string;
  newHostParticipantId: string;
}

interface MemberReadyPayload {
  participantId: string;
  ready: boolean;
  allReady: boolean;
}

// 재접속 유예(15초) 동안의 상태 변화. DISCONNECTED는 "나갔다"가 아니라 "끊겼고 아직 유예 중"이다 —
// 유예가 끝나 실제로 퇴장하면 그때 member:left가 따로 온다.
interface MemberConnectionPayload {
  participantId: string;
  connectionStatus: 'CONNECTED' | 'DISCONNECTED';
}

export function useRoomLobby(roomId: string, accessToken: string, participantId: string) {
  const [room, setRoom] = useState<RoomSnapshotResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 내가 강퇴당했는지. member:left는 방 전체 브로드캐스트라, 내 id + KICKED 조합이
  // "내가 쫓겨났다"는 유일한 신호다 — 별도 개인 채널로 알려주지 않는다.
  const [kicked, setKicked] = useState(false);

  useEffect(() => {
    let cancelled = false;
    roomApi.getRoom(roomId, accessToken)
      .then((snapshot) => {
        if (!cancelled) setRoom(snapshot);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : '방 정보 조회 실패');
      });
    return () => {
      cancelled = true;
    };
  }, [roomId, accessToken]);

  useEffect(() => {
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
          // setRoom 업데이터는 순수해야 하므로(StrictMode에서 두 번 실행) 여기서 감지한다.
          if (event.event === 'member:left') {
            const data = event.data as MemberLeftPayload;
            if (data.participantId === participantId && data.reason === 'KICKED') {
              setKicked(true);
            }
          }
          setRoom((prev) => {
            if (!prev) return prev;
            switch (event.event) {
              case 'member:joined': {
                const data = event.data as MemberJoinedPayload;
                if (prev.participants.some((p) => p.participantId === data.participantId)) {
                  return prev;
                }
                const joined: ParticipantResponse = {
                  participantId: data.participantId,
                  nickname: data.nickname,
                  role: 'MEMBER',
                  ready: false,
                  connectionStatus: 'CONNECTED',
                };
                return { ...prev, participants: [...prev.participants, joined] };
              }
              case 'member:left': {
                const data = event.data as MemberLeftPayload;
                return {
                  ...prev,
                  hostParticipantId: data.newHostParticipantId ?? prev.hostParticipantId,
                  participants: prev.participants.filter(
                    (p) => p.participantId !== data.participantId,
                  ),
                };
              }
              case 'host:changed': {
                const data = event.data as HostChangedPayload;
                return { ...prev, hostParticipantId: data.newHostParticipantId };
              }
              case 'member:connection-changed': {
                const data = event.data as MemberConnectionPayload;
                return {
                  ...prev,
                  participants: prev.participants.map((p) =>
                    p.participantId === data.participantId
                      ? { ...p, connectionStatus: data.connectionStatus }
                      : p,
                  ),
                };
              }
              case 'member:ready-updated': {
                const data = event.data as MemberReadyPayload;
                return {
                  ...prev,
                  participants: prev.participants.map((p) =>
                    p.participantId === data.participantId ? { ...p, ready: data.ready } : p,
                  ),
                };
              }
              default:
                return prev;
            }
          });
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken, participantId]);

  // 준비 상태는 STOMP 브로드캐스트(member:ready-updated)로도 돌아오지만, 그것만 의존하면
  // 이벤트가 늦거나 유실될 때 버튼이 "죽은 것처럼" 보인다 — API 응답을 즉시 로컬에 반영한다.
  // (STOMP 이벤트가 나중에 와도 같은 값이라 무해)
  const toggleReady = useCallback(
    async (ready: boolean) => {
      const result = await roomApi.updateReady(roomId, ready, accessToken);
      setRoom((prev) =>
        prev
          ? {
              ...prev,
              participants: prev.participants.map((p) =>
                p.participantId === result.participantId ? { ...p, ready: result.ready } : p,
              ),
            }
          : prev,
      );
    },
    [roomId, accessToken],
  );

  return { room, error, toggleReady, kicked };
}
