import { useLocalParticipant } from '@livekit/components-react';
import { Client } from '@stomp/stompjs';
import { useEffect, useRef, useState } from 'react';

interface RoomEvent<T> {
  event: string;
  roomId: string;
  data: T;
}

interface TurnStartedPayload {
  presenterId: string;
}

const TURN_FINISHED_EVENTS = new Set([
  'charades:answer-revealed',
  'charades:round-timeout',
  'charades:round-invalidated',
  'charades:game-ended',
]);

function presenterStorageKey(roomId: string) {
  return `camon:charades:presenter:${roomId}`;
}

function loadPresenterId(roomId: string) {
  return sessionStorage.getItem(presenterStorageKey(roomId));
}

function savePresenterId(roomId: string, presenterId: string | null) {
  const key = presenterStorageKey(roomId);
  if (presenterId) {
    sessionStorage.setItem(key, presenterId);
  } else {
    sessionStorage.removeItem(key);
  }
}

export function useCharadesMicrophone(
  roomId: string,
  accessToken: string,
  participantId: string,
) {
  const { isMicrophoneEnabled, localParticipant } = useLocalParticipant();
  const [presenterId, setPresenterId] = useState<string | null>(() => loadPresenterId(roomId));
  const [microphoneError, setMicrophoneError] = useState<string | null>(null);
  const microphoneBeforePresenting = useRef<boolean | null>(null);
  const isPresenter = presenterId === participantId;

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

          if (event.event === 'charades:turn-started') {
            const nextPresenterId = (event.data as TurnStartedPayload).presenterId;
            savePresenterId(roomId, nextPresenterId);
            setPresenterId(nextPresenterId);
            return;
          }
          if (event.event === 'game:started' || TURN_FINISHED_EVENTS.has(event.event)) {
            savePresenterId(roomId, null);
            setPresenterId(null);
          }
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);

  useEffect(() => {
    let cancelled = false;

    async function synchronizeMicrophone() {
      try {
        if (isPresenter) {
          if (microphoneBeforePresenting.current === null) {
            microphoneBeforePresenting.current = isMicrophoneEnabled;
          }
          if (isMicrophoneEnabled) {
            await localParticipant.setMicrophoneEnabled(false);
          }
        } else if (microphoneBeforePresenting.current !== null) {
          const shouldRestore = microphoneBeforePresenting.current;
          microphoneBeforePresenting.current = null;
          if (localParticipant.isMicrophoneEnabled !== shouldRestore) {
            await localParticipant.setMicrophoneEnabled(shouldRestore);
          }
        }
        if (!cancelled) {
          setMicrophoneError(null);
        }
      } catch (error) {
        if (!cancelled) {
          setMicrophoneError(
            error instanceof Error ? error.message : '마이크 상태를 변경하지 못했습니다.',
          );
        }
      }
    }

    void synchronizeMicrophone();
    return () => {
      cancelled = true;
    };
  }, [isPresenter, isMicrophoneEnabled, localParticipant]);

  return {
    isPresenter,
    microphoneMutedByRole: isPresenter && !isMicrophoneEnabled,
    microphoneError,
  };
}
