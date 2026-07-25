import { useDataChannel, useLocalParticipant } from '@livekit/components-react';
import { useCallback, useState } from 'react';

// 방 채팅 상태/전송 로직. 백엔드 채팅 도메인(spec의 chat:message-received)이 아직 미구현이라,
// 이미 방에 붙어 있는 LiveKit 데이터 채널로 프론트끼리 직접 주고받는다.
// 백엔드 채팅이 생기면 이 훅 내부만 STOMP 구독/전송으로 교체하면 된다.
//
// 훅으로 분리한 이유: 메시지 상태가 UI 컴포넌트(ChatPanel) 안에 있으면 로비↔게임 화면 전환으로
// 패널이 리마운트될 때 채팅 내역이 날아간다 — 상태는 방에 머무는 동안 살아있는 상위(RoomContent)가 소유.
const CHAT_TOPIC = 'chat';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export interface ChatMessage {
  id: string;
  nickname: string;
  text: string;
  mine: boolean;
}

export function useRoomChat() {
  const { localParticipant } = useLocalParticipant();
  const myNickname = localParticipant.name || '나';
  const [messages, setMessages] = useState<ChatMessage[]>([]);

  const { send } = useDataChannel(CHAT_TOPIC, (msg) => {
    try {
      const data = JSON.parse(decoder.decode(msg.payload)) as { nickname: string; text: string };
      setMessages((prev) => [...prev, { ...data, id: crypto.randomUUID(), mine: false }]);
    } catch {
      // 채팅이 아닌 다른 payload는 무시.
    }
  });

  const sendMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed) return;
      void send(encoder.encode(JSON.stringify({ nickname: myNickname, text: trimmed })), {
        reliable: true,
      });
      // 데이터 채널은 발신자 본인에게는 되돌아오지 않으므로 내 메시지는 로컬에 바로 추가한다.
      setMessages((prev) => [
        ...prev,
        { id: crypto.randomUUID(), nickname: myNickname, text: trimmed, mine: true },
      ]);
    },
    [send, myNickname],
  );

  return { messages, sendMessage };
}
