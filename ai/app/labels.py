# 물건 가져오기 AI 판정 라벨. 한국어 키는 백엔드 missions 제시어와 정확히 일치해야 한다.
#
# 선정 기준:
# - 1인 가구/자취방에서 쉽게 구할 수 있고, 서로 시각적으로 확실히 구분되는(원자성) 물건만
# - 재질 기준 세분화(머그컵/플라스틱컵)는 실테스트 탈락 — 조명/부착물에 쉽게 뒤집힘
# - 한 제시어가 여러 형태를 허용할 수 있다 (라면 = 봉지/컵 모두 OK, 가방 = 백팩/크로스백 모두 OK)
#   → 라벨당 프롬프트를 여러 개 두면 점수는 그중 최고점으로 합산된다 (main.py의 라벨별 max)
#
# 이전 버전 풀(50종 v1, 20종 v2)은 git 히스토리 참고.

# 프롬프트 템플릿 — 단일 기본형만 사용한다.
# ⚠ 촬영 맥락 템플릿("held up in a hand" 등)은 실측 후 롤백함 (2026-07-27):
#   손/동작 유사도가 물건 형태를 압도해서 (1) 빈손·대체물 시늉이 라벨 점수를 얻고
#   (2) 진짜 물건(0.457)과 시늉(0.398)의 간격이 위험하게 좁아짐.
#   단일 템플릿 실측은 정답 0.7+ / 차순위 _none으로 판별력이 훨씬 좋았다.
PROMPT_TEMPLATES = [
    "This is a photo of {}.",
]

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
    # 빈손 팬터마임 — 물건 없이 쥐는 시늉만 해도 가는 물건(숟가락 등)이 통과되던 문제 대응
    "an empty hand pretending to hold something",
    "an empty hand with fingers pinched together, holding nothing",
    # ⚠ 화면 사진 치팅 네거티브("...phone screen...")는 롤백함 (2026-07-27):
    #   진짜 휴대폰(특히 화면 꺼진 검은 폰)을 _none 0.646으로 흡수해버림 — 휴대폰이
    #   미션 라벨인 이상 본질적으로 충돌. 사진 치팅 방어는 "전원이 서로의 화면을 실시간으로
    #   보고 있다"는 사회적 레이어에 맡긴다.
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
