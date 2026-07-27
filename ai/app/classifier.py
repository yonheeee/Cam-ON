import torch
from PIL import Image
from transformers import AutoModel, AutoProcessor

# 모델은 장비에 맞춰 자동 선택 (AI_MODEL_ID env로 강제 지정 가능):
#   GPU(cuda) → so400m: 실사용 모델 — 정확도·해상도(384px) 우위, fp16 VRAM 3~4GB, 왕복 ~100ms
#   CPU       → base:   동작 확인용 소형(~800MB) — CPU에서 so400m은 프레임당 수 초라 비실용적
GPU_MODEL_ID = "google/siglip2-so400m-patch14-384"
CPU_MODEL_ID = "google/siglip2-base-patch16-224"
DEFAULT_MODEL_ID = GPU_MODEL_ID if torch.cuda.is_available() else CPU_MODEL_ID


class SiglipClassifier:
    """SigLIP 2 zero-shot 이미지-텍스트 매칭 래퍼.

    stateless — 게임/방 상태를 전혀 모르고 (이미지, 후보 프롬프트) → 점수만 계산한다.
    후보 프롬프트는 사실상 고정(미션 풀)이라 텍스트 임베딩을 1회만 계산해 캐시한다 —
    매 요청은 이미지 인코딩만 수행 (요청당 지연 대폭 감소).
    """

    def __init__(self, model_id: str = DEFAULT_MODEL_ID):
        self.model_id = model_id
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        self.model = (
            AutoModel.from_pretrained(model_id, torch_dtype=dtype).to(self.device).eval()
        )
        self.processor = AutoProcessor.from_pretrained(model_id)
        # 프롬프트 조합(tuple) → 정규화된 텍스트 임베딩 텐서
        self._text_cache: dict[tuple[str, ...], torch.Tensor] = {}

    @torch.no_grad()
    def _text_features(self, prompts: list[str]) -> torch.Tensor:
        key = tuple(prompts)
        cached = self._text_cache.get(key)
        if cached is None:
            inputs = self.processor(
                text=prompts,
                # SigLIP은 학습 시 max_length 패딩을 썼기 때문에 필수 (없으면 점수가 왜곡됨)
                padding="max_length",
                return_tensors="pt",
            ).to(self.device)
            features = self.model.get_text_features(**inputs)
            if not isinstance(features, torch.Tensor):
                features = features.pooler_output
            cached = features / features.norm(dim=-1, keepdim=True)
            self._text_cache[key] = cached
        return cached

    @torch.no_grad()
    def scores(self, image: Image.Image, prompts: list[str]) -> list[float]:
        """후보 프롬프트별 매칭 확률(0~1). SigLIP은 sigmoid라 각 값이 독립적인 확신도다."""
        text_embeds = self._text_features(prompts)
        image_inputs = self.processor(
            images=image.convert("RGB"), return_tensors="pt"
        ).to(self.device)
        image_embeds = self.model.get_image_features(**image_inputs)
        if not isinstance(image_embeds, torch.Tensor):
            image_embeds = image_embeds.pooler_output
        image_embeds = image_embeds / image_embeds.norm(dim=-1, keepdim=True)
        # SiglipModel.forward와 동일한 수식 (정규화 임베딩 내적 × scale + bias → sigmoid)
        logits = (
            image_embeds @ text_embeds.T * self.model.logit_scale.exp()
            + self.model.logit_bias
        )
        return torch.sigmoid(logits)[0].float().tolist()