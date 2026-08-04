package com.camon.domain.game.fetch.domain;

import java.util.List;

// AI 서버 labels.py와 문자열로 맞물리는 물건 가져오기 제시어 계약.
// 라벨을 추가하거나 바꾸기 전에 AI 서버 담당자와 공유하고 양쪽을 동시에 반영해야 한다.
public final class FetchObjectMissionCatalog {

    public static final String MISSION_TYPE = "OBJECT";

    // v3 (2026-08-04): "휴대폰" 제거 — 폰 화면 사진 치팅 방어로 AI가 폰 계열을 전부
    // _none(오답)으로 흡수하게 되어, 카탈로그에 남으면 클리어 불가능한 라운드가 된다.
    // 두루마리 휴지/옷걸이/책 추가 (labels.py v3와 동시 반영).
    public static final List<String> KEYWORDS = List.of(
        "마우스",
        "가위",
        "숟가락",
        "안경",
        "칫솔",
        "라면",
        "헤어드라이어",
        "우산",
        "그릇",
        "모자",
        "가방",
        "두루마리 휴지",
        "옷걸이",
        "책"
    );

    private FetchObjectMissionCatalog() {
    }
}
