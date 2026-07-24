import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { useState } from 'react';
import './ChatPanel.css';

// 방 채팅. 백엔드 채팅 도메인(spec의 chat:message-received)이 아직 미구현이라, 이미 방에 붙어
// 있는 LiveKit 데이터 채널로 프론트끼리 직접 주고받는다(손동작 인식 브로드캐스트와 같은 방식).
// 백엔드 채팅이 생기면 이 부분만 STOMP 구독/전송으로 교체하면 된다.
const CHAT_TOPIC = 'chat';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

interface ChatMessage {
  id: string;
  nickname: string;
  text: string;
  mine: boolean;
}

export function ChatPanel() {
  const { localParticipant } = useLocalParticipant();
  const myNickname = localParticipant.name || '나';
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState('');

  const { send } = useDataChannel(CHAT_TOPIC, (msg) => {
    try {
      const data = JSON.parse(decoder.decode(msg.payload)) as { nickname: string; text: string };
      setMessages((prev) => [...prev, { ...data, id: crypto.randomUUID(), mine: false }]);
    } catch {
      // 채팅이 아닌 다른 payload는 무시.
    }
  });

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const text = draft.trim();
    if (!text) return;
    void send(encoder.encode(JSON.stringify({ nickname: myNickname, text })), { reliable: true });
    // 데이터 채널은 발신자 본인에게는 되돌아오지 않으므로 내 메시지는 로컬에 바로 추가한다.
    setMessages((prev) => [...prev, { id: crypto.randomUUID(), nickname: myNickname, text, mine: true }]);
    setDraft('');
  };

  return (
    <div className="chat-panel">
      <div className="chat-panel__messages">
        {messages.map((m) => (
          <div key={m.id} className="chat-panel__message">
            <span className="chat-panel__nickname">{m.mine ? '나' : m.nickname}</span>: {m.text}
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
