// AI 서버(FastAPI, SigLIP 2) REST 클라이언트.
// Spring(8080)과 별개 서버라 base URL을 따로 관리한다 — 로컬 개발은 8100,
// 배포 시 GPU 서버 주소를 VITE_AI_BASE_URL로 주입(deploy/.env의 AI_BASE_URL).
//
// ?? 대신 ||를 쓴다: Docker 빌드에서 ARG를 안 넘기면 ENV가 빈 문자열로 들어오는데, 빈 문자열은
// nullish가 아니라서 ??로는 폴백이 안 걸린다. 그러면 base URL이 ''가 되어 같은 오리진으로
// 요청이 나가고(=/ai/... 404) 원인이 안 보인다.
const AI_BASE_URL =
  import.meta.env.VITE_AI_BASE_URL?.trim() || `http://${window.location.hostname}:8100`;

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
  /**
   * 서버가 살아 있는지. AI 서버는 GPU가 달린 개발용 머신에서 도는 일이 많아 (노트북이 곧
   * 인프라다) 꺼져 있는 상태가 흔하다 — 그걸 모르고 코스에 물건 가져오기를 담으면 게임이
   * 시작된 뒤 인식만 조용히 실패한다. 그래서 코스를 짜는 시점에 미리 물어본다.
   *
   * 인식 실패와 구분해서 다뤄야 하므로 예외를 던지지 않고 boolean만 돌려준다. 서버가 꺼져
   * 있으면 fetch가 네트워크 에러로 즉시 끊기지만, 방화벽에 먹혀 응답이 안 오는 경우엔 그냥
   * 매달리므로 타임아웃을 건다.
   */
  async isHealthy(timeoutMs = 3000): Promise<boolean> {
    try {
      const response = await fetch(`${AI_BASE_URL}/health`, {
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) return false;
      const body = (await response.json()) as { status?: string };
      return body.status === 'UP';
    } catch {
      return false;
    }
  },

  /** ROI crop 이미지를 보내 인식 결과를 받는다. 정답 판정이 아니라 분류 + 제시어 매칭 정보. */
  async detect(image: Blob, target: string): Promise<DetectionResult> {
    const form = new FormData();
    form.append('image', image, 'roi.jpg');
    form.append('target', target);
    const response = await fetch(
      `${AI_BASE_URL}/ai/games/${FETCH_GAME_ID}/fetch-object/detections`,
      { method: 'POST', body: form },
    );
    if (!response.ok) throw new AiApiError(`AI 서버 요청 실패 (HTTP ${response.status})`);
    const body = await response.json();
    return body.data as DetectionResult;
  },

  /** 미션 제시어 풀. 원래는 Spring GET .../fetch-object/mission이 제시어를 주지만
   *  (제시어 주체 = Spring), 그 API가 생기기 전까지 방장이 이 풀에서 뽑아 브로드캐스트한다. */
  async labels(): Promise<string[]> {
    const response = await fetch(`${AI_BASE_URL}/dev/labels`);
    if (!response.ok) throw new AiApiError('제시어 목록 조회 실패');
    return (await response.json()) as string[];
  },
};
