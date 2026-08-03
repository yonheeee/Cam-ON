import { useLocalParticipant } from '@livekit/components-react';
import { Track } from 'livekit-client';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
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
  // 지금 꺼져 있는 마이크가 "표현자라서 우리가 끈 것"인지 표시한다. 사용자가 스스로 끈 것과
  // 구분되어야 턴이 끝났을 때 되살릴 대상인지 판단할 수 있다.
  const mutedByRoleRef = useRef(false);
  // 표현자가 되기 직전의 마이크 상태(턴이 끝나면 되돌릴 값). 아직 신뢰할 수 없으면 null.
  const microphoneBeforePresenting = useRef<boolean | null>(null);
  // 마이크 조작을 한 줄로 세운다 — 끄기와 켜기가 겹치면 LiveKit의 실제 상태와 우리 판단이 어긋난다.
  const operationQueue = useRef<Promise<void>>(Promise.resolve());
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
      // STOMP는 인증 실패에도 reconnectDelay로 재연결을 계속 시도한다 — 죽은 토큰으로는
      // 영원히 실패하므로, 세션을 정리하고 첫 화면으로 되돌려 루프를 끊는다.
      onStompError: () => handleExpiredSession(),
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

  // 표현자일 때만 마이크를 끄고, 표현자가 아니게 되면 원래대로 되돌린다.
  //
  // 판단을 직전 조작이 끝난 뒤로 미루는 것이 핵심이다. 예전 구현은 React 상태(isMicrophoneEnabled)와
  // localParticipant의 즉시값을 섞어 읽어서, 끄기 요청이 아직 반영되지 않은 사이에 턴이 끝나면
  // "이미 원하는 상태다"라고 오판해 복구를 건너뛰었다. 그 직후 끄기가 반영되면 표현자가 아닌데도
  // 음소거가 그대로 남는다(짧은 턴·정답이 빨리 나온 턴에서 잘 재현됐다).
  useEffect(() => {
    let cancelled = false;

    const run = operationQueue.current.then(async () => {
      try {
        if (isPresenter) {
          // 사용자가 직접 정한 상태만 기억한다 — 우리가 끈 상태를 다시 기억하면 복구값이 꺼짐으로 덮인다.
          if (microphoneBeforePresenting.current === null && !mutedByRoleRef.current) {
            // 입장/재접속 직후엔 마이크 트랙이 아직 없어 isMicrophoneEnabled가 잠시 false다.
            // 그 값을 기억하면 턴이 끝난 뒤 꺼짐으로 "복구"하게 되므로 트랙이 생긴 뒤에만 믿는다.
            microphoneBeforePresenting.current = localParticipant.getTrackPublication(
              Track.Source.Microphone,
            )
              ? localParticipant.isMicrophoneEnabled
              : null;
          }
          if (localParticipant.isMicrophoneEnabled) {
            await localParticipant.setMicrophoneEnabled(false);
            mutedByRoleRef.current = true;
          }
        } else if (mutedByRoleRef.current) {
          // 표현자가 되기 직전 상태를 못 잡았으면 켜짐으로 되돌린다 — LiveKitRoom이 audio를 켠 채
          // 입장시키므로 그게 기본 상태다.
          const shouldRestore = microphoneBeforePresenting.current ?? true;
          if (localParticipant.isMicrophoneEnabled !== shouldRestore) {
            await localParticipant.setMicrophoneEnabled(shouldRestore);
          }
          // 복구가 성공한 뒤에만 표시를 지운다. 실패하면 남겨서 다음 실행이 다시 시도한다.
          mutedByRoleRef.current = false;
          microphoneBeforePresenting.current = null;
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
    });
    operationQueue.current = run;

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
