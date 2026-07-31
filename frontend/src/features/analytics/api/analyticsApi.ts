import { getOrCreateAnalyticsUserId } from '../lib/analyticsUserId';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? `http://${window.location.hostname}:8080`;

export type ClientAnalyticsEventName =
  | 'ROOM_ENTERED'
  | 'CAMERA_PERMISSION_RESULT'
  | 'RECOGNITION_TEST_RESULT'
  | 'RESULT_SCREEN_VIEWED'
  | 'NINJA_RECOGNITION_WINDOW';

export interface ClientAnalyticsEvent {
  eventId?: string;
  eventName: ClientAnalyticsEventName;
  occurredAt?: string;
  gameType?: string;
  sessionSeq?: number;
  roundNumber?: number;
  properties?: Record<string, unknown>;
}

async function recordEvents(
  roomId: string,
  accessToken: string,
  events: ClientAnalyticsEvent[],
): Promise<void> {
  const response = await fetch(`${BASE_URL}/api/rooms/${roomId}/analytics/events`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({
      analyticsUserId: getOrCreateAnalyticsUserId(),
      appVersion: import.meta.env.VITE_APP_VERSION ?? 'local',
      experimentVersion: import.meta.env.VITE_EXPERIMENT_VERSION ?? 'baseline',
      events: events.map((event) => ({
        ...event,
        eventId: event.eventId ?? crypto.randomUUID(),
        occurredAt: event.occurredAt ?? new Date().toISOString(),
      })),
    }),
  });

  if (!response.ok) {
    throw new Error(`Analytics request failed (HTTP ${response.status})`);
  }
}

export const analyticsApi = {
  recordEvents,

  recordEvent: (
    roomId: string,
    accessToken: string,
    event: ClientAnalyticsEvent,
  ) => recordEvents(roomId, accessToken, [event]),
};
