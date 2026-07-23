import { useDataChannel, useParticipants } from '@livekit/components-react';
import { GESTURE_RESULT_TOPIC, type GestureResultPayload } from '../lib/gestureBroadcast';
import { useGestureBoardStore } from '../store/gestureBoardStore';
import './GestureBoard.css';

// 참가자 전원의 손동작(스킬) 판정 결과를 한 화면에 모아 보여준다.
// 로컬 결과는 GesturePanel이 store에 직접 쓰고, 남의 결과는 여기서 데이터 채널을 구독해 받는다.
export function GestureBoard() {
  const participants = useParticipants();
  const entries = useGestureBoardStore((state) => state.entries);
  const setEntry = useGestureBoardStore((state) => state.setEntry);

  // msg.from이 비어 오는 경우가 있어서 전송자 식별은 payload.identity를 쓴다.
  useDataChannel(GESTURE_RESULT_TOPIC, (msg) => {
    try {
      const payload = JSON.parse(new TextDecoder().decode(msg.payload)) as GestureResultPayload;
      setEntry(payload.identity, payload);
    } catch {
      // 잘못된 payload는 무시
    }
  });

  return (
    <div className="gesture-board">
      <h2>참가자별 스킬 판정</h2>
      <ul>
        {participants.map((participant) => {
          const entry = entries[participant.identity];
          const isSkill = Boolean(entry?.comboLabel);
          return (
            <li key={participant.identity} className={isSkill ? 'gesture-board__row--skill' : undefined}>
              <span className="gesture-board__name">{participant.identity}</span>
              <span>{entry?.comboLabel ?? '대기 중'}</span>
              <span>{entry ? `${(entry.confidence * 100).toFixed(0)}%` : '-'}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
