import io
import os
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from PIL import Image

from .classifier import DEFAULT_MODEL_ID, SiglipClassifier
from .labels import MISSION_POOL, NEGATIVE_LABEL, build_candidates

STATIC_DIR = Path(__file__).parent / "static"

# 판정 임계값 — /test 페이지로 실제 물건들 점수 분포를 보고 조정한다.
CONFIDENCE_THRESHOLD = float(os.environ.get("AI_CONFIDENCE_THRESHOLD", "0.2"))
# 제시어 기준 판정에서 허용하는 순위 — 비슷한 라벨(종이컵↔플라스틱 컵)이 1등을 뺏어도
# 제시어가 이 순위 안에 들면 인정. 1로 두면 argmax와 동일해진다.
TARGET_RANK_LIMIT = int(os.environ.get("AI_TARGET_RANK_LIMIT", "3"))

app = FastAPI(title="Cam-ON AI Server", version="0.1.0")

# 프론트(브라우저)가 직접 호출하므로 CORS 필수 — 백엔드 CorsConfig와 같은 정책:
# 개발용 5173은 호스트 무관 허용 + 배포 오리진은 환경변수(FRONTEND_BASE_URL)로.
allow_origins = [os.environ["FRONTEND_BASE_URL"]] if "FRONTEND_BASE_URL" in os.environ else []
app.add_middleware(
    CORSMiddleware,
    allow_origins=allow_origins,
    allow_origin_regex=r"http://[^/]+:5173",
    allow_methods=["*"],
    allow_headers=["*"],
)

# 모델은 프로세스당 1회 로딩 (첫 요청이 아니라 서버 기동 시점에 — 첫 판정 지연 방지)
classifier = SiglipClassifier(os.environ.get("AI_MODEL_ID", DEFAULT_MODEL_ID))


@app.get("/health")
def health():
    return {"status": "UP", "model": classifier.model_id, "device": classifier.device}


# ---- 개발용 (실서비스 경로 아님) ----


@app.get("/test")
def test_page():
    """실시간 웹캠 판정 테스트 페이지. localhost로 접속해야 카메라 권한이 열린다."""
    return FileResponse(STATIC_DIR / "test.html")


@app.get("/dev/labels")
def dev_labels():
    """테스트 페이지 제시어 드롭다운용 — 미션 풀(mock) 한국어 라벨 목록."""
    return list(MISSION_POOL.keys())


# API 명세: POST /ai/games/{gameId}/fetch-object/detections
# "카메라 프레임 속 물체가 무엇인지 분류만 해서 응답(정답 여부는 판단 안 함)" —
# 정답/순위 판정은 Spring(/api/.../submissions)의 몫이고, 여기는 인식 결과만 준다.
# 응답은 백엔드와 동일한 {data: ...} envelope로 맞춰 프론트 클라이언트 패턴을 재사용한다.
@app.post("/ai/games/{game_id}/fetch-object/detections")
async def detect_object(
    game_id: int,
    image: UploadFile = File(...),
    # 이번 라운드 제시어(선택) — 미션 풀에 없는 제시어도 후보에 포함시키기 위한 힌트.
    # 정답 판단용이 아니다 (그건 Spring).
    target: str | None = Form(None),
):
    pil_image = Image.open(io.BytesIO(await image.read()))
    labels, prompts = build_candidates(target)
    scores = classifier.scores(pil_image, prompts)

    # 같은 라벨(_none 네거티브 3종 등)은 최고 점수 하나로 합친다
    best_by_label: dict[str, float] = {}
    for label, score in zip(labels, scores):
        best_by_label[label] = max(score, best_by_label.get(label, 0.0))

    detected_value, confidence = max(best_by_label.items(), key=lambda item: item[1])
    is_valid = detected_value != NEGATIVE_LABEL and confidence >= CONFIDENCE_THRESHOLD

    # 제시어 기준 판정 — "1등이 제시어냐(argmax)"가 아니라 "제시어 물건이 화면에 있냐"를 본다.
    # 홀더 낀 플라스틱 컵처럼 비슷한 라벨이 1등을 뺏어도, 제시어 자체 점수가 충분히 높고
    # 상위권이면 인정. (SigLIP은 sigmoid라 라벨별 점수가 독립적인 확신도여서 가능한 방식)
    target_score = None
    target_rank = None
    is_target_match = None
    if target and target in best_by_label:
        ranked = sorted(best_by_label.items(), key=lambda item: -item[1])
        target_score = best_by_label[target]
        target_rank = next(i + 1 for i, (label, _) in enumerate(ranked) if label == target)
        is_target_match = target_score >= CONFIDENCE_THRESHOLD and target_rank <= TARGET_RANK_LIMIT

    return {
        "data": {
            "gameId": game_id,
            "detectedValue": None if detected_value == NEGATIVE_LABEL else detected_value,
            "confidence": round(confidence, 4),
            "isValid": is_valid,
            # 제시어(target)를 보낸 경우에만 채워지는 제시어 기준 판정
            "targetScore": round(target_score, 4) if target_score is not None else None,
            "targetRank": target_rank,
            "isTargetMatch": is_target_match,
            # 디버깅/임계값 튜닝용 — 안정화되면 제거 가능
            "scores": {
                label: round(score, 4)
                for label, score in sorted(best_by_label.items(), key=lambda i: -i[1])[:5]
            },
        }
    }
