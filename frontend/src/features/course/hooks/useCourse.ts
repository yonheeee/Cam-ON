import { Client } from '@stomp/stompjs';
import { handleExpiredSession } from '../../session/lib/sessionExpiry';
import { useCallback, useEffect, useState } from 'react';
import {
  courseApi,
  CourseApiError,
  type CatalogGame,
  type Course,
  type CourseItemInput,
} from '../api/courseApi';

// 대기방의 코스 상태를 소유하는 훅. 초기 스냅샷은 REST로 받고, 이후 방장이 저장한 변경은
// member:game-updated 이벤트로 전원에게 전파된다(useRoomLobby와 동일한 연결 패턴).
//
// 게임 카탈로그(고를 수 있는 게임 + 라운드 허용 범위)도 같이 들고 있다 — 코스 편집 화면이
// 둘을 항상 함께 써서 분리하면 호출자가 두 훅을 조립해야 한다.
interface RoomEvent<T> {
  event: string;
  data: T;
}

export function useCourse(roomId: string, accessToken: string) {
  const [course, setCourse] = useState<Course | null>(null);
  const [games, setGames] = useState<CatalogGame[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      courseApi.getCourse(roomId, accessToken),
      courseApi.getGames(accessToken),
    ])
      .then(([loadedCourse, loadedGames]) => {
        if (cancelled) return;
        setCourse(loadedCourse);
        setGames(loadedGames);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof CourseApiError ? err.message : '게임 구성 조회 실패');
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
      connectHeaders: { Authorization: `Bearer ${accessToken}` },
      reconnectDelay: 3000,
      // STOMP는 인증 실패에도 reconnectDelay로 재연결을 계속 시도한다 — 죽은 토큰으로는
      // 영원히 실패하므로, 세션을 정리하고 첫 화면으로 되돌려 루프를 끊는다.
      onStompError: () => handleExpiredSession(),
      onConnect: () => {
        client.subscribe(`/topic/rooms/${roomId}`, (message) => {
          const event = JSON.parse(message.body) as RoomEvent<unknown>;
          if (event.event === 'member:game-updated') {
            // payload가 코스 전체(diff 아님)라 그냥 갈아끼운다.
            setCourse(event.data as Course);
          }
        });
      },
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [roomId, accessToken]);

  // 방장만 호출한다(서버도 방장 여부를 검증한다). 저장 성공 시 응답을 즉시 반영해,
  // 브로드캐스트가 늦어도 방장 화면이 멈춘 것처럼 보이지 않게 한다.
  const saveCourse = useCallback(
    async (items: CourseItemInput[]): Promise<boolean> => {
      setSaving(true);
      setError(null);
      try {
        const saved = await courseApi.updateCourse(roomId, items, accessToken);
        setCourse(saved);
        return true;
      } catch (err) {
        setError(err instanceof CourseApiError ? err.message : '게임 구성 저장 실패');
        return false;
      } finally {
        setSaving(false);
      }
    },
    [roomId, accessToken],
  );

  return { course, games, error, saving, saveCourse };
}
