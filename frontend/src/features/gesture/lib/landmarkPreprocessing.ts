// app.py의 calc_landmark_list / pre_process_landmark / pre_process_point_history를 그대로 포팅.
export type Point = [number, number];

export interface NormalizedLandmark {
  x: number;
  y: number;
}

export function calcLandmarkList(
  landmarks: NormalizedLandmark[],
  imageWidth: number,
  imageHeight: number,
): Point[] {
  return landmarks.map((landmark) => [
    Math.min(Math.floor(landmark.x * imageWidth), imageWidth - 1),
    Math.min(Math.floor(landmark.y * imageHeight), imageHeight - 1),
  ]);
}

export function preProcessLandmark(landmarkList: Point[]): number[] {
  const [baseX, baseY] = landmarkList[0];
  const relative = landmarkList.map(([x, y]): Point => [x - baseX, y - baseY]);
  const flat = relative.flat();
  const maxValue = Math.max(...flat.map(Math.abs)) || 1;
  return flat.map((v) => v / maxValue);
}

// app.py의 combine_two_hand_landmarks 포팅. 양손이 다 보일 때만 호출된다 —
// Left->Right 순서로 이어붙여 84차원 "조합 포즈" 입력을 만든다.
export function combineTwoHandLandmarks(left: number[], right: number[]): number[] {
  return [...left, ...right];
}
