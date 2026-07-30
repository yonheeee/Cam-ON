// 손동작(제스처) 이름 → 손모양 라인아트 이미지 경로.
// 키는 DB gesture.name(= 분류기 라벨, KEYPOINT_LABELS)이고 값은 public/assets/손모양의 파일명이다.
// 둘의 표기가 어긋나는 곳이 있어서(Horse→horse, sailor_moon→sailormoon) 매핑을 명시한다.
// girl_V(브이)는 이미지가 아직 없다 — 호출부가 null을 받으면 한글 라벨로 폴백한다.
const FILE_BY_GESTURE: Record<string, string> = {
  snake: 'snake',
  mouse: 'mouse',
  Horse: 'horse',
  cow: 'cow',
  rabbit: 'rabbit',
  cat: 'cat',
  spider: 'spider',
  sailor_moon: 'sailormoon',
};

/** 손모양 이미지 URL. 대응 이미지가 없으면 null. */
export function gestureImage(gestureName: string): string | null {
  const file = FILE_BY_GESTURE[gestureName];
  return file ? `/assets/손모양/${file}.png` : null;
}
