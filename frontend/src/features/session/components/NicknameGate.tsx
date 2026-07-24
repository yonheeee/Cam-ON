import { useState } from 'react';
import { createSession, SessionApiError } from '../api/sessionApi';
import { saveSession, type StoredSession } from '../lib/sessionStorage';

// 백엔드 CreateSessionRequest 검증 규칙(@Size(max=8), @Pattern("^[\p{L}\p{N}]+$"))과 동일 —
// 서버 왕복 없이 바로 피드백 주려고 프론트에도 미러링. 최종 검증은 항상 백엔드가 한다.
const NICKNAME_PATTERN = /^[\p{L}\p{N}]{1,8}$/u;

interface NicknameGateProps {
  onReady: (session: StoredSession) => void;
}

export function NicknameGate({ onReady }: NicknameGateProps) {
  const [nickname, setNickname] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!NICKNAME_PATTERN.test(nickname)) {
      setError('닉네임은 공백/기호 없이 1~8자여야 합니다.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const session = await createSession(nickname);
      saveSession(session);
      onReady(session);
    } catch (err) {
      setError(err instanceof SessionApiError ? err.message : '세션 생성 실패');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <h1>닉네임 입력</h1>
      <form onSubmit={handleSubmit}>
        <label>
          닉네임
          <input
            value={nickname}
            onChange={(event) => setNickname(event.target.value)}
            maxLength={8}
            disabled={submitting}
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? '생성 중...' : '입장'}
        </button>
      </form>
      {error && <p>{error}</p>}
    </div>
  );
}
