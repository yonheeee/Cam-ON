# 물건 가져오기 미션 후보 풀 (임시 mock).
# 최종적으로는 MySQL missions 테이블이 원본이고, 이 파일은 백엔드 미션 확정 전까지의 기본값이다.
#
# 선정 기준: 1인 가구/자취방에서 쉽게 구할 수 있는 물건 + 제시어 간 원자성.
# 단, 원자성은 "형태/용도" 기준으로만 나눈다 — "재질" 기준 세분화(머그컵/플라스틱 컵/종이컵)는
# 실테스트에서 탈락: 홀더 낀 플라스틱 컵이 종이컵으로 인식되는 등 재질은 조명/부착물에
# 쉽게 뒤집히는 모호한 기준이었다. 그래서 컵은 종이컵 하나만 둔다.
#
# SigLIP은 영어 프롬프트가 가장 안정적이라 ko(제시어) → en(프롬프트용 구문)을 함께 관리한다.
# 프롬프트 템플릿은 SigLIP 공식 문서 권장 형태("This is a photo of {}.")를 쓴다.

PROMPT_TEMPLATE = "This is a photo of {}."

# 제시어(한국어) → 영어 프롬프트 구문
MISSION_POOL: dict[str, str] = {
    # ---- 주방/식사 ----
    "종이컵": "a disposable paper cup",
    "숟가락": "a metal spoon",
    "젓가락": "a pair of chopsticks",
    "포크": "a fork",
    "그릇": "a bowl",
    "접시": "a plate",
    "컵라면": "a cup of instant noodles",
    "봉지라면": "an unopened packet of instant ramen noodles",
    "즉석밥": "a plastic container of instant microwavable rice",
    "생수병": "a plastic water bottle",
    "캔음료": "an aluminum beverage can",
    "프라이팬": "a frying pan",
    "냄비": "a cooking pot",
    "국자": "a ladle",
    "가위": "scissors",
    "주방세제": "a bottle of dish soap",
    # ---- 생활/욕실 ----
    "칫솔": "a toothbrush",
    "치약": "a tube of toothpaste",
    "수건": "a towel",
    "휴지": "a roll of toilet paper",
    "물티슈": "a pack of wet wipes",
    "샴푸통": "a bottle of shampoo",
    "빗": "a hair comb",
    "손톱깎이": "a nail clipper",
    "면봉": "cotton swabs",
    "헤어드라이어": "a hair dryer",
    # ---- 책상/전자기기 ----
    "휴대폰": "a smartphone",
    "노트북": "a laptop computer",
    "무선 이어폰": "wireless earbuds",
    "유선 이어폰": "wired earphones with a cable",
    "충전기": "a wall charger power adapter",
    "충전 케이블": "a USB charging cable",
    "마우스": "a computer mouse",
    "키보드": "a computer keyboard",
    "리모컨": "a remote control",
    "보조배터리": "a portable power bank",
    "볼펜": "a ballpoint pen",
    "공책": "a paper notebook",
    "책": "a book",
    "포스트잇": "a pad of sticky notes",
    "테이프": "a roll of adhesive tape",
    # ---- 의류/기타 ----
    "안경": "a pair of glasses",
    "모자": "a baseball cap",
    "양말": "a pair of socks",
    "마스크": "a disposable face mask",
    "우산": "an umbrella",
    "지갑": "a wallet",
    "열쇠": "a key",
    "인형": "a stuffed toy",
    "옷걸이": "a clothes hanger",
}

# 오인식 방지용 네거티브 — "아무것도 안 들고 있는" 상황이 어떤 물건으로 오판되지 않게
# 후보에 항상 포함시킨다. detected_value가 이것으로 나오면 "인식 실패"로 취급.
NEGATIVE_LABEL = "_none"
NEGATIVE_PROMPTS = [
    "a person's face",
    "an empty hand",
    "an empty room",
]


def build_candidates(extra_target: str | None = None) -> tuple[list[str], list[str]]:
    """(라벨 목록, 프롬프트 목록)을 만든다. extra_target이 풀에 없는 새 제시어면 후보에 추가."""
    labels = list(MISSION_POOL.keys())
    prompts = [PROMPT_TEMPLATE.format(en) for en in MISSION_POOL.values()]

    if extra_target and extra_target not in MISSION_POOL:
        labels.append(extra_target)
        # 풀에 없는 제시어는 그대로 영어 프롬프트로 시도 (프론트가 영어명을 보낸 경우)
        prompts.append(PROMPT_TEMPLATE.format(extra_target))

    for negative in NEGATIVE_PROMPTS:
        labels.append(NEGATIVE_LABEL)
        prompts.append(PROMPT_TEMPLATE.format(negative))

    return labels, prompts
