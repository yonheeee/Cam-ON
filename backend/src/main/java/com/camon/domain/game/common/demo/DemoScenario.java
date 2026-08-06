package com.camon.domain.game.common.demo;

import java.util.List;

/**
 * 발표 시연용 고정 시나리오.
 *
 * <p>시연 모드({@code Room.demoMode})가 켜진 방에서는 게임별 콘텐츠 선택이 랜덤 대신 여기 적힌
 * 값으로 고정된다. 게임 규칙 코드를 고치는 게 아니라 "무엇을 뽑을지"만 갈아끼우는 것이라,
 * 발표가 끝나면 이 클래스와 각 서비스의 {@code demoMode} 분기만 지우면 원래대로 돌아온다.
 *
 * <p>세 게임의 시연 값이 한곳에 모여 있어야 발표 직전에 "무슨 순서로 뭐가 나오는지"를 한 화면에서
 * 확인·수정할 수 있다 — 그래서 게임별 패키지로 흩지 않고 common 아래 한 파일에 둔다.
 */
public final class DemoScenario {

    // ---- 닌자 「손은 눈보다 빠르다」 ----

    /**
     * 교환 순서대로 제시되는 술법. 데미지가 초기 HP(100)와 같아 한 교환에 한 명씩 탈락하므로,
     * 4인 시연이면 딱 세 교환 = 이 목록 한 바퀴로 판이 끝난다(콤보 2단 → 3단 → 4단으로 난이도가
     * 올라가는 순서라 시연 흐름이 그대로 클라이맥스가 된다).
     *
     * <p>이름은 {@code skill.name}과 정확히 일치해야 한다(DevNinjaDataSeeder가 넣는 값).
     * 못 찾은 이름은 조용히 건너뛰고, 하나도 못 찾으면 평소대로 전체 셔플로 되돌아간다.
     */
    public static final List<String> NINJA_SKILL_NAMES = List.of(
        "냥냥펀치",
        "봉선화의 술",
        "나선환"
    );

    /** 시연에서는 모든 술법이 한 방 — NinjaGameService.INITIAL_HP와 같은 값이어야 한다. */
    public static final int NINJA_SKILL_DAMAGE = 100;

    // ---- 물건 가져오기 「엄마! 내 물건 어딨어?」 ----

    /** 5개(기본)는 발표 시간에 안 맞는다 — 3개로 줄인다. */
    public static final int FETCH_TOTAL_ROUNDS = 3;

    /**
     * 시연에서 제시될 물건과 그 순서. 발표자가 미리 챙겨 둘 수 있어야 하므로 랜덤을 쓰지 않는다.
     * 값은 반드시 {@code FetchObjectMissionCatalog.KEYWORDS}(=AI 서버 labels.py)에 있는 것이어야
     * 하고, DB에 해당 mission이 없으면 평소대로 랜덤 선택으로 되돌아간다.
     */
    public static final List<String> FETCH_KEYWORDS = List.of(
        "안경",
        "가위",
        "마우스"
    );

    // ---- 몸으로 말해요 「말하지 않아도 알아요」 ----

    /** 시연에서 고정할 주제. 아래 제시어들이 이 주제에 들어 있다. */
    public static final String CHARADES_TOPIC_NAME = "동물";

    /**
     * 턴 순서대로 나올 제시어. 주제가 뭘로 설정돼 있든 시연 모드에서는
     * {@link #CHARADES_TOPIC_NAME} 주제로 갈아끼운 뒤 이 순서대로 뽑는다.
     */
    public static final List<String> CHARADES_KEYWORDS = List.of(
        "악어",
        "알파카",
        "코끼리",
        "기린"
    );

    // ---- 코스 진행 ----

    /** 코스 항목의 라운드 수를 시연용으로 바꾼다(게임 이름은 games.name = GameSessionStarter.gameName). */
    public static int roundCount(String gameName, int requested) {
        // 라운드 수를 여기서도 바꿔 두지 않으면 룰 설명 화면과 세션 메타데이터엔 5라운드가
        // 남아서, 실제로는 3라운드만 도는 게임과 화면이 어긋난다.
        return "FETCH_OBJECT".equals(gameName) ? FETCH_TOTAL_ROUNDS : requested;
    }

    private DemoScenario() {
    }
}
