// model/keypoint_classifier/keypoint_classifier_label.csv, point_history_classifier_label.csv 그대로 포팅.
export const KEYPOINT_LABELS = [
  'Open',
  'Close',
  'Pointer',
  'OK',
  'V',
  'Thumbs Up',
  'Rock',
  'Horse',
] as const;

export const POINT_HISTORY_LABELS = ['Stop', 'Clockwise', 'Counter Clockwise', 'Move'] as const;

// app.py의 SKILL_EFFECT_LABELS
export const SKILL_EFFECT_LABELS = new Set(['OK', 'V', 'Thumbs Up', 'Rock', 'Horse']);
