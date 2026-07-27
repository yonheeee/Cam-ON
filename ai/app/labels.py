# 물건 가져오기 미션 후보 풀 (임시 mock).
# 최종적으로는 MySQL missions 테이블이 원본이고, 이 파일은 백엔드 미션 확정 전까지의 기본값이다.
#
# 선정 기준:
# - 1인 가구/자취방에서 쉽게 구할 수 있고, 서로 시각적으로 확실히 구분되는(원자성) 물건만
# - 재질 기준 세분화(머그컵/플라스틱컵)는 실테스트 탈락 — 조명/부착물에 쉽게 뒤집힘
# - 한 제시어가 여러 형태를 허용할 수 있다 (라면 = 봉지/컵 모두 OK, 가방 = 백팩/크로스백 모두 OK)
#   → 라벨당 프롬프트를 여러 개 두면 점수는 그중 최고점으로 합산된다 (main.py의 라벨별 max)
#
# 이전 버전 풀(50종 v1, 20종 v2)은 git 히스토리 참고.

# 프롬프트 템플릿 — 게임의 실제 촬영 조건(웹캠 앞에서 손에 들고 보여줌)을 담은 변형을
# 함께 사용한다. 라벨별 점수는 모든 (템플릿 × 형태) 조합 중 최고점이라 변형 추가는 손해가 없고,
# 텍스트 임베딩 캐시 덕에 런타임 비용도 없다 (기동 후 첫 요청에서 1회 인코딩).
PROMPT_TEMPLATES = [
    "This is a photo of {}.",
    "This is a webcam photo of {} held up in a hand.",
    "A person holding {} in front of a webcam.",
]

# 네거티브는 이미 완결된 구문이라 기본 템플릿 하나만 쓴다
PROMPT_TEMPLATE = PROMPT_TEMPLATES[0]

# 제시어(한국어) → 허용하는 영어 프롬프트 구문들
MISSION_POOL: dict[str, list[str]] = {
    "휴대폰": ["a smartphone"],
    "마우스": [
        "a computer mouse",
        # 버티컬(에르고) 마우스가 일반 프롬프트로는 0.4 문턱을 못 넘어서 변형 추가
        "a vertical ergonomic computer mouse",
        "a wireless computer mouse",
    ],
    "가위": ["scissors"],
    "숟가락": ["a metal spoon"],
    "안경": ["a pair of glasses"],
    "칫솔": ["a toothbrush"],
    "라면": [
        "an unopened packet of instant ramen noodles",
        "a cup of instant noodles",
    ],
    "헤어드라이어": ["a hair dryer"],
    "우산": ["an umbrella"],
    "그릇": ["a bowl", "a plate", "a ceramic dish"],
    "모자": ["a baseball cap", "a hat", "a beanie"],
    "가방": ["a backpack", "a crossbody bag", "a handbag", "a tote bag"],
    # 여기에 자유롭게 추가 — "제시어": ["허용 형태 1", "허용 형태 2", ...]
}

# 오인식 방지용 네거티브. 두 부류로 나뉜다:
# 1) "아무것도 없음" 계열 — 빈 손/얼굴/배경이 물건으로 오판되지 않게
# 2) "정체불명 물건" 계열 (open-set 거부) — 풀에 없는 물건을 들었을 때 특정 라벨이 아니라
#    이쪽에 흡수되게 해서, 미등록 물건이 오인 판정되는 걸 막는다.
# 여러 개여도 같은 라벨(_none)로 합산(최고점)되므로 rank를 1자리만 차지한다.
NEGATIVE_LABEL = "_none"
NEGATIVE_PROMPTS = [
    # 아무것도 없음
    "a person's face",
    "an empty hand",
    "an empty room",
    # 정체불명 물건 (open-set 흡수)
    "a hand holding an unidentifiable object",
    "a hand holding some random household item",
]


def build_candidates(extra_target: str | None = None) -> tuple[list[str], list[str]]:
    """(라벨 목록, 프롬프트 목록)을 만든다. 라벨당 프롬프트가 여러 개면 같은 라벨을 반복해서
    붙인다 — 점수 집계(main.py)가 같은 라벨을 최고점으로 합치므로 앙상블처럼 동작한다.
    extra_target이 풀에 없는 새 제시어면 후보에 추가."""
    labels: list[str] = []
    prompts: list[str] = []

    for ko, variants in MISSION_POOL.items():
        for variant in variants:
            for template in PROMPT_TEMPLATES:
                labels.append(ko)
                prompts.append(template.format(variant))

    if extra_target and extra_target not in MISSION_POOL:
        labels.append(extra_target)
        # 풀에 없는 제시어는 그대로 영어 프롬프트로 시도 (프론트가 영어명을 보낸 경우)
        prompts.append(PROMPT_TEMPLATE.format(extra_target))

    for negative in NEGATIVE_PROMPTS:
        labels.append(NEGATIVE_LABEL)
        prompts.append(PROMPT_TEMPLATE.format(negative))

    return labels, prompts
