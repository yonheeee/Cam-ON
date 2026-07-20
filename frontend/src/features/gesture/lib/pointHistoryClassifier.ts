import weights from '../models/point_history_classifier_weights.json';
import { argmax, mlpForward, type DenseLayerWeights } from './mlp';
import { POINT_HISTORY_LABELS } from './labels';

const layers = weights as DenseLayerWeights[];
const SCORE_THRESHOLD = 0.5;
const INVALID_INDEX = 0; // 'Stop'

// model/point_history_classifier/point_history_classifier.py의 __call__ 포팅.
// score_th 미만이면 invalid_value(0, 'Stop')로 취급하는 것까지 동일하게 맞췄다.
export function classifyPointHistory(preprocessedPointHistory: number[]): { index: number; label: string } {
  const probabilities = mlpForward(preprocessedPointHistory, layers);
  let index = argmax(probabilities);
  if (probabilities[index] < SCORE_THRESHOLD) index = INVALID_INDEX;
  return { index, label: POINT_HISTORY_LABELS[index] };
}
