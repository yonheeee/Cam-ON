import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { PixelConfirmModal } from '../../system/components/PixelConfirmModal';
import { RoomApiError } from '../api/roomApi';
import { EntryModal } from '../components/EntryModal';
import { enterRoom, NICKNAME_PATTERN, ROOM_CODE_PATTERN, type EnterMode } from '../lib/enterRoom';
import './EntryPage.css';

const BLOCKING_ERRORS: Record<string, { title: string; message: string }> = {
  ROOM_ALREADY_STARTED: {
    title: '게임 중인 방이에요',
    message: '이미 게임이 시작되어 입장할 수 없어요. 게임이 끝난 뒤 다시 참여해주세요.',
  },
  ROOM_FULL: {
    title: '방에 입장하지 못했어요',
    message: '방의 인원이 모두 찼어요. 다른 방에 참여해주세요.',
  },
};

const SERVER_ERROR = {
  title: '서버에 연결하지 못했어요',
  message: '연결 상태를 확인한 뒤 다시 시도해주세요.',
};

export function NicknamePage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [nickname, setNickname] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<{ title: string; message: string } | null>(null);
  const [serverError, setServerError] = useState(false);

  const modeParam = searchParams.get('mode');
  const mode: EnterMode | null =
    modeParam === 'create' || modeParam === 'join' ? modeParam : null;
  const roomCode = (searchParams.get('code') ?? '').toUpperCase();
  // 확정: 인원 선택 단계 폐지 — 모든 방은 4인 정원 고정.
  // (players 파라미터가 남아 있어도 무시한다. 자리가 비어도 현재 인원 전원 준비면 시작 가능.)
  const maxPlayers = 4;

  if (mode === null) return <Navigate to="/" replace />;
  if (mode === 'join' && !ROOM_CODE_PATTERN.test(roomCode)) {
    return <Navigate to="/join" replace />;
  }

  const backTo = mode === 'join' ? `/join?code=${roomCode}` : '/';

  const submitNickname = async () => {
    if (submitting) return;
    if (!NICKNAME_PATTERN.test(nickname)) {
      setError('닉네임은 공백과 특수문자 없이 1~8자로 입력해주세요.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      const roomId = await enterRoom({
        mode,
        nickname,
        roomCode,
        maxPlayers: maxPlayers ?? 4,
      });
      navigate(`/rooms/${roomId}`);
    } catch (caught) {
      const blockedBy =
        caught instanceof RoomApiError && caught.code
          ? BLOCKING_ERRORS[caught.code]
          : undefined;

      if (blockedBy) {
        setBlocked(blockedBy);
      } else if (caught instanceof RoomApiError && caught.code === 'ROOM_NOT_FOUND') {
        navigate(`/join?code=${roomCode}&error=room-not-found`, { replace: true });
      } else if (caught instanceof RoomApiError && caught.code === 'NICKNAME_DUPLICATED') {
        setError('이미 사용 중인 닉네임이에요.');
      } else {
        setServerError(true);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    void submitNickname();
  };

  if (serverError) {
    return (
      <PixelConfirmModal
        title={SERVER_ERROR.title}
        message={SERVER_ERROR.message}
        confirmLabel="다시 시도"
        cancelLabel="메인으로"
        onConfirm={() => {
          setServerError(false);
          void submitNickname();
        }}
        onCancel={() => navigate('/', { replace: true })}
      />
    );
  }

  if (blocked) {
    return (
      <PixelConfirmModal
        title={blocked.title}
        message={blocked.message}
        confirmLabel="메인으로"
        onConfirm={() => navigate('/', { replace: true })}
      />
    );
  }

  return (
    <EntryModal onClose={() => !submitting && navigate(backTo)}>
      <form className="entry__card" onSubmit={handleSubmit}>
        <header className="entry__header">
          <h1 className="entry__title">닉네임 입력</h1>
          <button
            type="button"
            className="entry__close"
            aria-label="닫기"
            onClick={() => navigate(backTo)}
            disabled={submitting}
          >
            ×
          </button>
        </header>

        <label className="entry__field">
          <span className="entry__label">닉네임</span>
          <input
            className={`entry__input${error ? ' entry__input--error' : ''}`}
            value={nickname}
            onChange={(event) => {
              setNickname(event.target.value);
              setError(null);
            }}
            maxLength={8}
            placeholder="민수"
            autoFocus
            autoComplete="off"
            disabled={submitting}
            aria-label="닉네임"
            aria-invalid={Boolean(error)}
          />
        </label>

        <button
          type="submit"
          className="entry__submit"
          disabled={submitting || nickname.length === 0}
        >
          {submitting ? '입장 중...' : mode === 'create' ? '방 만들기' : '참여'}
        </button>

        <p className={`entry__feedback${error ? ' entry__feedback--error' : ''}`}>
          {error ?? '게임에서 사용할 닉네임을 입력해주세요.'}
        </p>
      </form>
    </EntryModal>
  );
}
