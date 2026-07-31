// AI 서버(FastAPI, SigLIP 2) REST 클라이언트.
// Spring(8080)과 별개 서버라 base URL을 따로 관리한다 — 로컬 개발은 8100,
// 배포 시 GPU 서버 주소를 VITE_AI_BASE_URL로 주입.
const AI_BASE_URL =
  import.meta.env.VITE_AI_BASE_URL ?? `http://${window.location.hostname}:8100`;

// 물건 가져오기 gameId — 코스 도메인 확정 전 임시 고정 (닌자=1 관례에 이어 2)
export const FETCH_GAME_ID = 2;

export interface DetectionResult {
  detectedValue: string | null;
  confidence: number;
  isValid: boolean;
  /** 제시어(target)를 보낸 경우에만 채워지는 제시어 기준 판정 */
  targetScore: number | null;
  targetRank: number | null;
  isTargetMatch: boolean | null;
  scores: Record<string, number>;
}

export class AiApiError extends Error {}

export const aiApi = {
  /** ROI crop 이미지를 보내 인식 결과를 받는다. 정답 판정이 아니라 분류 + 제시어 매칭 정보. */
  async detect(
    image: Blob,
    target: string,
    signal?: AbortSignal,
  ): Promise<DetectionResult> {
    const form = new FormData();
    form.append('image', image, 'roi.jpg');
    form.append('target', target);
    const response = await fetch(
      `${AI_BASE_URL}/ai/games/${FETCH_GAME_ID}/fetch-object/detections`,
      { method: 'POST', body: form, signal },
    );
    if (!response.ok) throw new AiApiError(`AI 서버 요청 실패 (HTTP ${response.status})`);
    const body = await response.json();
    return body.data as DetectionResult;
  },

  /** 미션 제시어 풀. 원래는 Spring GET .../fetch-object/mission이 제시어를 주지만
   *  (제시어 주체 = Spring), 그 API가 생기기 전까지 방장이 이 풀에서 뽑아 브로드캐스트한다. */
};
