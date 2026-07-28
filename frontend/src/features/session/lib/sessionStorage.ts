// AGENTS.md 공통 기능 요구사항: 게스트 토큰(UUID) 발급 → sessionStorage 저장.
// 탭 닫으면 사라지는 sessionStorage를 쓰는 이유도 동일 문서 기준 — 회원가입 없는 임시 세션이라
// localStorage처럼 영구 보관할 이유가 없다.
import type { CreateSessionResult } from '../api/sessionApi';

const STORAGE_KEY = 'camon.session';

export type StoredSession = CreateSessionResult;

export function saveSession(session: StoredSession): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function loadSession(): StoredSession | null {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StoredSession;
  } catch {
    return null;
  }
}

export function clearSession(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}
