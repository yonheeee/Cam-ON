import { useEffect, useRef, useState } from 'react';
import type { ChatMessage } from '../hooks/useRoomChat';
import { mixSfxVolume } from '../../sound/lib/sfxVolume';
import './ChatPanel.css';

// 로비 채팅 전송 버튼 — Figma `Material / send` 16×16 (텍스트 "전송"은 쓰지 않는다)
const SendIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d="M3.4 20.4 21.85 12 3.4 3.6v6.53L15.6 12 3.4 13.87v6.53Z" />
  </svg>
);

interface ChatPanelProps {
  messages: ChatMessage[];
  onSend: (text: string) => void;
  /** floating: 게임 중 우하단 오버레이(다크) / docked: 로비 사이드바에 내장(Plaza 테마) */
  variant?: 'floating' | 'docked';
  /** 닉네임 → 표시 색 (로비에서 플레이어 대표색을 입힐 때). 없으면 기본색 */
  nicknameColorFor?: (nickname: string) => string | undefined;
}

// 채팅 UI. 메시지 상태/전송은 useRoomChat 훅이 소유하고 여기는 표시만 한다
// (로비↔게임 전환으로 리마운트돼도 내역이 유지되게).
export function ChatPanel({ messages, onSend, variant = 'floating', nicknameColorFor }: ChatPanelProps) {
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const sendSoundRef = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    const audio = new Audio('/assets/sounds/button-click.mp3');
    audio.preload = 'auto';
    sendSoundRef.current = audio;

    return () => {
      audio.pause();
      sendSoundRef.current = null;
    };
  }, []);

  // 새 메시지가 오면 맨 아래로 스크롤
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!draft.trim()) return;

    const sendSound = sendSoundRef.current;
    if (sendSound) {
      // 재사용하는 Audio라 재생 직전에 음량을 계산한다(슬라이더 변경 즉시 반영).
      sendSound.volume = mixSfxVolume(0.6);
      sendSound.currentTime = 0;
      void sendSound.play().catch(() => {
        // 효과음 재생 실패가 채팅 전송을 막아서는 안 된다.
      });
    }

    onSend(draft);
    setDraft('');
  };

  return (
    <div className={`chat-panel chat-panel--${variant}`}>
      <div className="chat-panel__messages" ref={listRef}>
        {messages.map((m) => (
          <div key={m.id} className="chat-panel__message">
            {/* 내 메시지도 "나" 대신 닉네임으로 — 색은 로비의 플레이어 대표색을 따른다.
                docked(로비)는 Figma대로 닉네임이 윗줄, floating(게임 중)은 "닉네임: 본문" 한 줄 */}
            <span className="chat-panel__nickname" style={{ color: nicknameColorFor?.(m.nickname) }}>
              {m.nickname}
            </span>
            <span className="chat-panel__text">{m.text}</span>
          </div>
        ))}
      </div>
      <form className="chat-panel__form" onSubmit={handleSubmit}>
        <input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={variant === 'docked' ? '메시지를 입력하세요' : '메시지 입력'}
          maxLength={200}
        />
        <button
          type="submit"
          data-button-sound="none"
          aria-label="전송"
          title="전송"
        >
          {variant === 'docked' ? SendIcon : '전송'}
        </button>
      </form>
    </div>
  );
}
