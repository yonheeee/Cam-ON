"""SigLIP 모델/임계값 검증 스크립트.

집에 있는 물건 몇 개를 웹캠/폰으로 찍어 samples/ 폴더에 넣고 돌리면,
모델별로 어떤 라벨에 몇 점을 주는지 비교표를 출력한다.
→ base vs so400m 중 뭘 쓸지, 임계값(AI_CONFIDENCE_THRESHOLD)을 얼마로 할지 감 잡는 용도.

사용법 (ai/ 폴더에서):
    python scripts/validate.py samples/*.jpg
    python scripts/validate.py samples/spoon1.jpg --models google/siglip2-base-patch16-224
    python scripts/validate.py samples/*.jpg --models google/siglip2-base-patch16-224 google/siglip2-so400m-patch14-384
"""

import argparse
import glob
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PIL import Image

from app.classifier import SiglipClassifier
from app.labels import build_candidates

DEFAULT_MODELS = [
    "google/siglip2-base-patch16-224",
    "google/siglip2-so400m-patch14-384",
]


def main() -> None:
    parser = argparse.ArgumentParser(description="SigLIP 후보 라벨 점수 비교")
    parser.add_argument("images", nargs="+", help="이미지 경로 (glob 가능)")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    parser.add_argument("--top", type=int, default=5, help="상위 몇 개 라벨을 보여줄지")
    args = parser.parse_args()

    image_paths: list[str] = []
    for pattern in args.images:
        matched = glob.glob(pattern)
        image_paths.extend(matched if matched else [pattern])

    labels, prompts = build_candidates()

    for model_id in args.models:
        print(f"\n{'=' * 70}\n모델: {model_id}")
        load_start = time.perf_counter()
        classifier = SiglipClassifier(model_id)
        print(f"로딩 {time.perf_counter() - load_start:.1f}s / device={classifier.device}")

        for path in image_paths:
            image = Image.open(path)
            infer_start = time.perf_counter()
            scores = classifier.scores(image, prompts)
            elapsed_ms = (time.perf_counter() - infer_start) * 1000

            ranked = sorted(zip(labels, scores), key=lambda item: -item[1])[: args.top]
            summary = "  ".join(f"{label}={score:.3f}" for label, score in ranked)
            print(f"  {Path(path).name:<24} ({elapsed_ms:>6.0f}ms)  {summary}")


if __name__ == "__main__":
    main()
