import { FilesetResolver, HandLandmarker } from '@mediapipe/tasks-vision';

// MediaPipe HandLandmarker 생성 설정. 인식 루프(useHandGestureRecognition)와 게임 시작 전
// 사전 점검(StartPreflightModal)이 같은 값으로 만들어야 "점검은 통과했는데 게임에선 실패"가
// 생기지 않으므로 여기 한 곳에 둔다.
//
// WASM과 모델을 외부 CDN에서 받는다 — 사내망/오프라인에서는 이 다운로드가 막혀 손동작 인식이
// 통째로 안 되는데, 예전엔 그게 게임 화면에 들어간 뒤에야 드러났다.
const WASM_BASE = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm';
const MODEL_URL =
  'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task';
// app.py의 mp.solutions.hands 기본 인자(min_detection_confidence=0.7, min_tracking_confidence=0.5)와 맞춤.
const MIN_HAND_DETECTION_CONFIDENCE = 0.7;
const MIN_TRACKING_CONFIDENCE = 0.5;

// 판정에 쓰는 손은 2개(왼손+오른손)뿐인데 4개를 받는 이유: HandLandmarker는 "가까운 손"이 아니라
// 자기 detection 점수 순으로 상한까지 내놓는다. 2로 두면 뒤쪽을 지나가는 사람의 손이나 손처럼
// 생긴 물체가 상위에 올라오는 순간 플레이어의 손 하나가 밀려나 조합 판정이 죽었다. 후보를
// 넉넉히 받아서 그중 가까운 손·가운데 손을 lib/handSelection.ts가 직접 고른다.
// 손 하나당 랜드마크 추론이 한 번 더 도는 만큼 프레임 비용이 늘지만, GPU 델리게이트에서
// 인식 루프가 30fps를 유지하는 선이라 판정이 죽는 것보다 낫다.
const NUM_HANDS = 4;

/**
 * 양손 랜드마커를 만든다. WASM + 모델(약 8MB)을 CDN에서 받으므로 첫 호출은 느리다 —
 * 브라우저가 캐시하므로 두 번째부터는 빠르다(사전 점검이 워밍업 역할도 한다).
 *
 * 다 쓴 인스턴스는 호출부가 close()로 닫아야 GPU 리소스가 반납된다.
 */
export async function createHandLandmarker(): Promise<HandLandmarker> {
  const vision = await FilesetResolver.forVisionTasks(WASM_BASE);
  return HandLandmarker.createFromOptions(vision, {
    baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
    runningMode: 'VIDEO',
    numHands: NUM_HANDS,
    minHandDetectionConfidence: MIN_HAND_DETECTION_CONFIDENCE,
    minTrackingConfidence: MIN_TRACKING_CONFIDENCE,
  });
}
