import { useEffect, useRef, useState } from 'react';
import type { ChatMessage } from '../hooks/useRoomChat';
import './ChatPanel.css';

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

  // 새 메시지가 오면 맨 아래로 스크롤
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!draft.trim()) return;
    onSend(draft);
    setDraft('');
  };

  return (
    <div className={`chat-panel chat-panel--${variant}`}>
      <div className="chat-panel__messages" ref={listRef}>
        {messages.map((m) => (
          <div key={m.id} className="chat-panel__message">
            {/* 내 메시지도 "나" 대신 닉네임으로 — 색은 로비의 플레이어 대표색을 따른다 */}
            <span className="chat-panel__nickname" style={{ color: nicknameColorFor?.(m.nickname) }}>
              {m.nickname}
            </span>
            : {m.text}
          </div>
        ))}
      </div>
      <form className="chat-panel__form" onSubmit={handleSubmit}>
        <input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="메시지 입력"
          maxLength={200}
        />
        <button type="submit">전송</button>
      </form>
    </div>
  );
}
