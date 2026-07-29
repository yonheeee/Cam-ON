package com.camon.dev;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
public class DevGameCatalogSeeder implements ApplicationRunner {

    // games.name — 세션 시작 전략을 고르는 키이기도 하므로 이 문자열이 곧 계약이다.
    public static final String NINJA = "NINJA";
    public static final String FETCH_OBJECT = "FETCH_OBJECT";
    public static final String CHARADES = "CHARADES";

    private static final String CHARADES_MISSION_TYPE = "CHARADES";
    private static final String DEFAULT_DIFFICULTY = "NORMAL";

    private final GameRepository gameRepository;
    private final MissionTopicRepository missionTopicRepository;
    private final MissionRepository missionRepository;

    public DevGameCatalogSeeder(
        GameRepository gameRepository,
        MissionTopicRepository missionTopicRepository,
        MissionRepository missionRepository
    ) {
        this.gameRepository = gameRepository;
        this.missionTopicRepository = missionTopicRepository;
        this.missionRepository = missionRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 라운드 범위는 2026-07-29 확정 명세를 따른다:
        // 닌자 3~10 / 물건가져오기 (참여자 수)~10 / 몸으로말해요 1~3.
        // 물건가져오기의 하한 "참여자 수"는 게임 시작 시점에야 알 수 있으므로 min_rounds=NULL로
        // 표현하고 애플리케이션(CourseService)이 계산한다.
        seedGame(
            NINJA,
            "제시된 손동작 콤보를 가장 빨리 완성해 공격권을 얻고, 최후의 1인이 남을 때까지 겨룬다.",
            2, 4, 3, 10
        );
        Game fetchObject = seedGame(
            FETCH_OBJECT,
            "제시된 물건을 제한시간 안에 카메라 앞으로 가져온다. 빨리 가져온 순서대로 점수를 얻는다.",
            2, 4, null, 10
        );
        Game charades = seedGame(
            CHARADES,
            "고른 주제의 제시어를 말 없이 몸으로 설명하고, 나머지 참가자가 채팅으로 정답을 맞힌다.",
            3, 4, 1, 3
        );

        seedFetchObjectMissions(fetchObject);
        seedCharadesTopics(charades);
    }

    // name이 unique라 "없으면 추가"만으로 멱등하다. 이미 있으면 그 row를 그대로 쓴다
    // (인원/라운드 범위를 운영에서 조정해 뒀을 수 있어 덮어쓰지 않는다).
    private Game seedGame(
        String name,
        String description,
        int minPlayers,
        int maxPlayers,
        Integer minRounds,
        Integer maxRounds
    ) {
        return gameRepository.findByName(name).orElseGet(() -> {
            log.info("[Seed] games : '{}' 없음 → 추가", name);
            return gameRepository.save(Game.builder()
                .name(name)
                .description(description)
                .minPlayers(minPlayers)
                .maxPlayers(maxPlayers)
                .minRounds(minRounds)
                .maxRounds(maxRounds)
                .isActive(true)
                .build());
        });
    }

    private void seedFetchObjectMissions(Game fetchObject) {
        Set<String> existingKeywords = missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                fetchObject.getGameId(),
                FetchObjectMissionCatalog.MISSION_TYPE
            )
            .stream()
            .map(Mission::getKeyword)
            .collect(Collectors.toSet());

        Set<String> unexpectedKeywords = existingKeywords.stream()
            .filter(keyword ->
                !FetchObjectMissionCatalog.KEYWORDS.contains(keyword)
            )
            .collect(Collectors.toSet());
        if (!unexpectedKeywords.isEmpty()) {
            // AI 서버 labels.py와 문자열 계약이 어긋난 데이터는 자동으로 수정/삭제하지 않는다.
            // 라벨 변경은 양쪽 서버 담당자에게 먼저 공유한 뒤 함께 반영해야 한다.
            log.warn(
                "[Seed] missions : FETCH_OBJECT 계약 외 활성 키워드 발견 {} — 자동 변경하지 않음",
                unexpectedKeywords
            );
        }

        List<String> missingKeywords = FetchObjectMissionCatalog.KEYWORDS.stream()
            .filter(keyword -> !existingKeywords.contains(keyword))
            .toList();
        if (missingKeywords.isEmpty()) {
            return;
        }

        log.info(
            "[Seed] missions : FETCH_OBJECT 누락 제시어 {}개 추가 {}",
            missingKeywords.size(),
            missingKeywords
        );
        missingKeywords.forEach(keyword -> missionRepository.save(
            Mission.builder()
                .game(fetchObject)
                .topic(null)
                .missionType(FetchObjectMissionCatalog.MISSION_TYPE)
                .keyword(keyword)
                // 영어 프롬프트는 AI 서버 labels.py가 관리하므로 백엔드에는 저장하지 않는다.
                .targetLabel(null)
                .difficulty(DEFAULT_DIFFICULTY)
                .isActive(true)
                .build()
        ));
    }

    private void seedCharadesTopics(Game charades) {
        if (!missionTopicRepository
            .findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(charades.getGameId())
            .isEmpty()) {
            return; // 이미 주제가 있으면 손대지 않는다.
        }
        log.info("[Seed] mission_topics : CHARADES 주제 없음 → 기본 주제/제시어 추가");

        // 제시어는 한 턴에 하나씩 소진되고 재사용되지 않는다(selectUnusedMission). 최대 인원 4명 x
        // 최대 3라운드 = 12턴이 이론상 한 게임의 상한이지만, 라운드 상한을 다시 올리더라도
        // 제시어 부족으로 게임이 끊기지 않도록 주제마다 40개(4명 x 10라운드 분량)를 채워 둔다.
        seedTopic(charades, "동물", List.of(
            "코끼리", "기린", "펭귄", "캥거루", "고양이", "강아지", "원숭이", "사자",
            "토끼", "거북이", "뱀", "독수리", "상어", "고래", "다람쥐", "호랑이",
            "곰", "여우", "늑대", "사슴", "낙타", "얼룩말", "하마", "코알라",
            "판다", "개구리", "달팽이", "문어", "게", "나비", "벌", "거미",
            "말", "돼지", "소", "양", "닭", "오리", "공룡", "박쥐"
        ));
        seedTopic(charades, "음식", List.of(
            "피자", "치킨", "라면", "김밥", "떡볶이", "햄버거", "초밥", "삼겹살",
            "짜장면", "탕수육", "비빔밥", "된장찌개", "김치찌개", "갈비탕", "냉면", "만두",
            "붕어빵", "호떡", "팝콘", "아이스크림", "케이크", "도넛", "샌드위치", "파스타",
            "스테이크", "카레", "수제비", "죽", "계란찜", "옥수수", "고구마", "감자탕",
            "순대", "곱창", "회", "수박", "바나나", "포도", "딸기", "멜론"
        ));
        seedTopic(charades, "직업", List.of(
            "의사", "간호사", "소방관", "경찰관", "교사", "요리사", "미용사", "가수",
            "배우", "화가", "운동선수", "축구선수", "야구선수", "발레리나", "지휘자", "피아니스트",
            "농부", "어부", "목수", "택배기사", "버스기사", "파일럿", "승무원", "군인",
            "판사", "변호사", "기자", "아나운서", "개발자", "디자이너", "사진작가", "마술사",
            "광부", "우주비행사", "수의사", "약사", "치과의사", "바리스타", "제빵사", "청소부"
        ));
    }

    private void seedTopic(Game game, String topicName, List<String> keywords) {
        MissionTopic topic = missionTopicRepository.save(MissionTopic.builder()
            .game(game)
            .name(topicName)
            .isActive(true)
            .build());
        keywords.forEach(keyword -> missionRepository.save(Mission.builder()
            .game(game)
            .topic(topic)
            .missionType(CHARADES_MISSION_TYPE)
            .keyword(keyword)
            .difficulty(DEFAULT_DIFFICULTY)
            .isActive(true)
            .build()));
        log.info("[Seed] mission_topics : '{}' 주제에 제시어 {}개 추가", topicName, keywords.size());
    }
}
