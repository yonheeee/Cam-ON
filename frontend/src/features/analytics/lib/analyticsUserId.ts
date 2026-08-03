const STORAGE_KEY = 'camon.analytics.user-id';

export function getOrCreateAnalyticsUserId(): string {
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (stored) return stored;

  const created = crypto.randomUUID();
  window.localStorage.setItem(STORAGE_KEY, created);
  return created;
}
