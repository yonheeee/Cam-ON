package com.camon.dev;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// games / mission_topics / missions 시드 데이터. DevNinjaDataSeeder와 같은 이유로 존재한다 —
// ddl-auto: none인데 마이그레이션 도구가 없어서, 기준 데이터를 앱 시작 시 코드로 채운다.
//
// 이 세 테이블은 코스 기능이 생기기 전까지 코드에서 조회된 적이 없어서(games는 리포지토리조차
// 없었다) 어떤 환경에 무엇이 들어있는지 보장이 없었다. 코스 설정 화면이 GET /api/games로
// 목록을 받아 오는 순간부터는 비어 있으면 "고를 게 하나도 없는" 화면이 되므로 여기서 보장한다.
//
// 멱등성: 이미 있는 데이터는 절대 건드리지 않는다(수동으로 넣어둔 운영 데이터를 덮어쓰면 안 된다).
// - games는 name이 unique라 없는 것만 추가한다.
// - 주제/제시어는 해당 게임에 주제가 하나도 없을 때만 통째로 넣는다.
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
        seedGame(
            NINJA,
            "제시된 손동작 콤보를 가장 빨리 완성해 공격권을 얻고, 최후의 1인이 남을 때까지 겨룬다.",
            2, 4, 3, 10
        );
        seedGame(
            FETCH_OBJECT,
            "제시된 물건을 제한시간 안에 카메라 앞으로 가져온다. 빨리 가져온 순서대로 점수를 얻는다.",
            // min_rounds가 null이면 "참여자 수"를 최소 라운드로 앱이 계산한다.
            2, 4, null, 10
        );
        Game charades = seedGame(
            CHARADES,
            "고른 주제의 제시어를 말 없이 몸으로 설명하고, 나머지 참가자가 채팅으로 정답을 맞힌다.",
            // 요구사항 명세엔 3~10라운드로 적혀 있지만 실제 구현은 1라운드부터 성립한다
            // (1라운드 = 참가자 전원이 한 번씩 표현). 코드가 실제로 지원하는 범위로 맞춘다.
            3, 4, 1, 10
        );

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

    private void seedCharadesTopics(Game charades) {
        if (!missionTopicRepository
            .findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(charades.getGameId())
            .isEmpty()) {
            return; // 이미 주제가 있으면 손대지 않는다.
        }
        log.info("[Seed] mission_topics : CHARADES 주제 없음 → 기본 주제/제시어 추가");

        // 제시어는 한 턴에 하나씩 소진되고 재사용되지 않는다(selectUnusedMission). 최대 인원 4명 x
        // 최대 10라운드 = 40턴이 이론상 한 게임의 상한이므로, 주제마다 40개를 채워 라운드 수를
        // 최대로 올려도 제시어 부족으로 게임이 끊기지 않게 한다.
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
