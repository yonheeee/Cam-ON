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

export function preProcessPointHistory(
  pointHistory: Point[],
  imageWidth: number,
  imageHeight: number,
): number[] {
  if (pointHistory.length === 0) return [];
  const [baseX, baseY] = pointHistory[0];
  const relative = pointHistory.map(([x, y]): Point => [(x - baseX) / imageWidth, (y - baseY) / imageHeight]);
  return relative.flat();
}
