import { useEffect, useState } from 'react';

// 실제로는 게스트 세션 발급(COM01_ACC04: UUID 토큰 → sessionStorage)이 이 역할을 하는데,
// 대기방/세션 도메인이 아직 없어서 대신 LiveKit identity로부터 결정적으로 UUID 형태
// 문자열을 만든다. "결정적"이어야 하는 이유: 같은 identity("alice" 등)면 어느 탭에서
// 계산해도 항상 같은 UUID가 나와야, 새 브로드캐스트 채널 없이도 LiveKit이 이미 주는
// useParticipants()의 identity 목록만으로 서로를 공격 대상으로 지정할 수 있다.
const cache = new Map<string, string>();

export async function resolveParticipantId(identity: string): Promise<string> {
  const cached = cache.get(identity);
  if (cached) return cached;

  const data = new TextEncoder().encode(`plaiground-dev:${identity}`);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(digest).slice(0, 16);
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  const uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;

  cache.set(identity, uuid);
  return uuid;
}

export function useParticipantId(identity: string | undefined): { id: string | null; error: string | null } {
  const [id, setId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!identity) {
      setId(null);
      return;
    }
    let cancelled = false;
    resolveParticipantId(identity)
      .then((resolved) => {
        if (!cancelled) setId(resolved);
      })
      .catch((err: unknown) => {
        // 실패를 조용히 삼키면 버튼이 이유 없이 영원히 disabled로 남는다 — 눈에 보이게 남긴다.
        if (!cancelled) setError(err instanceof Error ? err.message : '참가자 ID 계산 실패');
      });
    return () => {
      cancelled = true;
    };
  }, [identity]);

  return { id, error };
}
