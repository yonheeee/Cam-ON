import torch
from PIL import Image
from transformers import AutoModel, AutoProcessor

# 기본은 CPU 개발용 base 모델. GPU 서버에선 환경변수로 so400m을 지정한다:
#   AI_MODEL_ID=google/siglip2-so400m-patch14-384
DEFAULT_MODEL_ID = "google/siglip2-base-patch16-224"


class SiglipClassifier:
    """SigLIP 2 zero-shot 이미지-텍스트 매칭 래퍼.

    stateless — 게임/방 상태를 전혀 모르고 (이미지, 후보 프롬프트) → 점수만 계산한다.
    """

    def __init__(self, model_id: str = DEFAULT_MODEL_ID):
        self.model_id = model_id
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        self.model = (
            AutoModel.from_pretrained(model_id, torch_dtype=dtype).to(self.device).eval()
        )
        self.processor = AutoProcessor.from_pretrained(model_id)

    @torch.no_grad()
    def scores(self, image: Image.Image, prompts: list[str]) -> list[float]:
        """후보 프롬프트별 매칭 확률(0~1). SigLIP은 sigmoid라 각 값이 독립적인 확신도다
        (softmax처럼 합이 1이 아님) — 그래서 절대 임계값 판정에 적합하다."""
        inputs = self.processor(
            text=prompts,
            images=image.convert("RGB"),
            # SigLIP은 학습 시 max_length 패딩을 썼기 때문에 필수 (없으면 점수가 왜곡됨)
            padding="max_length",
            return_tensors="pt",
        ).to(self.device)
        outputs = self.model(**inputs)
        probs = torch.sigmoid(outputs.logits_per_image)[0].float()
        return probs.tolist()
