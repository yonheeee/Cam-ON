// model/keypoint_classifier/keypoint_classifier_label.csv 그대로 포팅.
// v1(9클래스, 전부 양손 조합 포즈)부터는 Open/Close/Pointer/OK 같은 한손 클래스가 없다 —
// 데이터가 없어서 라벨 자체가 삭제됨 (hand-gesture-recognition-mediapipe/README.md 참고).
export const KEYPOINT_LABELS = [
  'snake',
  'girl_V',
  'mouse',
  'Horse',
  'cow',
  'rabbit',
  'cat',
  'spider',
  'sailor_moon',
] as const;

// app.py의 COMBO_SKILL_EFFECT_LABELS: 지금은 9개 전부 양손 조합 스킬이라 라벨 전체와 동일하다.
export const SKILL_EFFECT_LABELS = new Set<string>(KEYPOINT_LABELS);
