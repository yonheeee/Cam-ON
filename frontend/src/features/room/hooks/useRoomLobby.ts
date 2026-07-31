import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useState } from 'react';
import { roomApi, type ParticipantResponse, type RoomSnapshotResponse } from '../api/roomApi';

// course/hooks/useCourseProgress.ts와 동일한 연결 패턴(brokerURL, connectHeaders, reconnectDelay) —
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

// 코스 종합 결과에서 한 명이 "방으로 돌아가기"를 눌렀다. 개별 복귀라 이 사람 타일만 "게임 중"을
// 떼면 된다(내 화면 전환은 useCourseProgress가 따로 담당).
interface MemberReturnedPayload {
  participantId: string;
  ready: boolean;
  roomReopened: boolean;
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
      // STOMP는 인증 실패에도 reconnectDelay로 재연결을 계속 시도한다 — 죽은 토큰으로는
      // 영원히 실패하므로, 세션을 정리하고 첫 화면으로 되돌려 루프를 끊는다.
      onStompError: () => handleExpiredSession(),
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
                  // 새로 입장하면 대기방에 있다 — 방이 WAITING일 때만 입장이 열린다.
                  inLobby: true,
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
              case 'course:member-returned': {
                // 한 명이 결과 화면을 접고 대기방으로 들어왔다 — 그 타일의 "게임 중"을 뗀다.
                // ready도 payload로 함께 온다(방장은 true, 나머지는 false로 리셋됨).
                const data = event.data as MemberReturnedPayload;
                return {
                  ...prev,
                  // 가장 먼저 돌아온 사람이 방을 WAITING으로 되돌렸다. 상태를 같이 갱신하지
                  // 않으면 스냅샷의 FINISHED가 남아 다음 코스 시작 판단이 어긋난다.
                  status: data.roomReopened ? 'WAITING' : prev.status,
                  participants: prev.participants.map((p) =>
                    p.participantId === data.participantId
                      ? { ...p, inLobby: true, ready: data.ready }
                      : // 방이 재개방되는 순간 서버가 전원 준비를 해제하므로 함께 반영한다.
                        data.roomReopened
                        ? { ...p, ready: false }
                        : p,
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
