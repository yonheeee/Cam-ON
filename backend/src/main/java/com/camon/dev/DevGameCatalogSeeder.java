package com.camon.dev;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.charades.domain.CharadesMissionCatalog;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
        CharadesMissionCatalog.TOPICS.forEach(topicSpec ->
            seedTopic(charades, topicSpec)
        );
    }

    private void seedTopic(
        Game game,
        CharadesMissionCatalog.TopicSpec topicSpec
    ) {
        MissionTopic topic = missionTopicRepository
            .findByGameGameIdAndName(game.getGameId(), topicSpec.name())
            .orElseGet(() -> {
                log.info(
                    "[Seed] mission_topics : CHARADES '{}' 주제 추가",
                    topicSpec.name()
                );
                return missionTopicRepository.save(
                    MissionTopic.builder()
                        .game(game)
                        .name(topicSpec.name())
                        .isActive(true)
                        .build()
                );
            });
        if (!topic.getIsActive()) {
            log.warn(
                "[Seed] mission_topics : CHARADES '{}' 주제가 비활성 상태 — 자동 변경하지 않음",
                topicSpec.name()
            );
            return;
        }

        Map<String, Mission> existingByKeyword = missionRepository
            .findAllByGameGameIdAndTopicTopicIdAndMissionType(
                game.getGameId(),
                topic.getTopicId(),
                CHARADES_MISSION_TYPE
            )
            .stream()
            .collect(Collectors.toMap(
                Mission::getKeyword,
                Function.identity(),
                (first, ignored) -> first
            ));

        List<CharadesMissionCatalog.MissionSpec> missing =
            topicSpec.missions().stream()
                .filter(spec -> !existingByKeyword.containsKey(spec.keyword()))
                .toList();
        missing.forEach(spec -> missionRepository.save(
            Mission.builder()
                .game(game)
                .topic(topic)
                .missionType(CHARADES_MISSION_TYPE)
                .keyword(spec.keyword())
                .difficulty(spec.difficulty())
                .isActive(spec.active())
                .build()
        ));
        if (!missing.isEmpty()) {
            log.info(
                "[Seed] missions : CHARADES '{}' 주제에 누락 제시어 {}개 추가",
                topicSpec.name(),
                missing.size()
            );
        }
    }
}
