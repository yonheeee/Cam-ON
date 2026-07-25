import weights from '../models/keypoint_classifier_weights.json';
import { argmax, mlpForward, type DenseLayerWeights } from './mlp';
import { KEYPOINT_LABELS } from './labels';

const layers = weights as DenseLayerWeights[];

// model/keypoint_classifier/keypoint_classifier.py의 KeyPointClassifier.__call__ 포팅.
export function classifyKeyPoint(
  preprocessedLandmarkList: number[],
): { index: number; label: string; confidence: number } {
  const probabilities = mlpForward(preprocessedLandmarkList, layers);
  const index = argmax(probabilities);
  return { index, label: KEYPOINT_LABELS[index], confidence: probabilities[index] };
}
