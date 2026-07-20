export const GESTURE_RESULT_TOPIC = 'gesture-result';

// msg.from이 dev 서버 환경에서 비어 오는 경우가 있어서, 전송자 식별을
// LiveKit 메타데이터에 기대지 않고 payload 안에 직접 담는다.
export interface GestureResultPayload {
  identity: string;
  handSignLabel: string;
  fingerGestureLabel: string;
}
