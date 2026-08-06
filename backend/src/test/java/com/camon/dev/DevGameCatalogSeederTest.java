package com.camon.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.charades.domain.CharadesMissionCatalog;
import com.camon.domain.game.fetch.domain.FetchObjectMissionCatalog;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DevGameCatalogSeederTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private MissionTopicRepository missionTopicRepository;

    @Mock
    private MissionRepository missionRepository;

    private DevGameCatalogSeeder seeder;
    private Game fetchObject;

    @BeforeEach
    void setUp() {
        Game ninja = game(1L, DevGameCatalogSeeder.NINJA);
        fetchObject = game(2L, DevGameCatalogSeeder.FETCH_OBJECT);
        Game charades = game(3L, DevGameCatalogSeeder.CHARADES);

        when(gameRepository.findByName(DevGameCatalogSeeder.NINJA))
            .thenReturn(Optional.of(ninja));
        when(gameRepository.findByName(DevGameCatalogSeeder.FETCH_OBJECT))
            .thenReturn(Optional.of(fetchObject));
        when(gameRepository.findByName(DevGameCatalogSeeder.CHARADES))
            .thenReturn(Optional.of(charades));
        // 물건 가져오기 시더 테스트에 몸으로 말해요 저장 건수가 섞이지 않도록,
        // CSV 카탈로그 전체가 이미 저장된 상태로 준비한다.
        long topicId = 1L;
        for (CharadesMissionCatalog.TopicSpec topicSpec
            : CharadesMissionCatalog.TOPICS) {
            MissionTopic topic = MissionTopic.builder()
                .topicId(topicId++)
                .game(charades)
                .name(topicSpec.name())
                .isActive(true)
                .build();
            lenient().when(missionTopicRepository.findByGameGameIdAndName(
                charades.getGameId(),
                topicSpec.name()
            )).thenReturn(Optional.of(topic));
            lenient().when(missionRepository
                .findAllByGameGameIdAndTopicTopicIdAndMissionType(
                    charades.getGameId(),
                    topic.getTopicId(),
                    DevGameCatalogSeeder.CHARADES
                ))
                .thenReturn(topicSpec.missions().stream()
                    .map(spec -> charadesMission(
                        charades,
                        topic,
                        spec
                    ))
                    .toList());
        }

        seeder = new DevGameCatalogSeeder(
            gameRepository,
            missionTopicRepository,
            missionRepository
        );
    }

    @Test
    void seedsExactFetchObjectKeywordContract() throws Exception {
        when(missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                fetchObject.getGameId(),
                FetchObjectMissionCatalog.MISSION_TYPE
            ))
            .thenReturn(List.of());

        seeder.run(null);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        // 개수 canary — 카탈로그를 바꾸면 여기서 걸린다 (v3: 휴대폰 제거 + 3종 추가 = 14)
        verify(missionRepository, times(14)).save(captor.capture());
        List<Mission> saved = captor.getAllValues();

        assertThat(saved)
            .extracting(Mission::getKeyword)
            .containsExactlyElementsOf(FetchObjectMissionCatalog.KEYWORDS);
        assertThat(saved).allSatisfy(mission -> {
            assertThat(mission.getGame()).isEqualTo(fetchObject);
            assertThat(mission.getTopic()).isNull();
            assertThat(mission.getMissionType())
                .isEqualTo(FetchObjectMissionCatalog.MISSION_TYPE);
            assertThat(mission.getTargetLabel()).isNull();
            assertThat(mission.getDifficulty()).isEqualTo("NORMAL");
            assertThat(mission.getIsActive()).isTrue();
        });
    }

    @Test
    void seedsOnlyMissingFetchObjectKeywords() throws Exception {
        List<Mission> existing = FetchObjectMissionCatalog.KEYWORDS.stream()
            .limit(2)
            .map(this::fetchMission)
            .toList();
        when(missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                fetchObject.getGameId(),
                FetchObjectMissionCatalog.MISSION_TYPE
            ))
            .thenReturn(existing);

        seeder.run(null);

        ArgumentCaptor<Mission> captor = ArgumentCaptor.forClass(Mission.class);
        verify(missionRepository, times(FetchObjectMissionCatalog.KEYWORDS.size() - 2))
            .save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(Mission::getKeyword)
            .containsExactlyElementsOf(
                FetchObjectMissionCatalog.KEYWORDS.subList(
                    2,
                    FetchObjectMissionCatalog.KEYWORDS.size()
                )
            );
    }

    @Test
    void doesNotDuplicateCompleteFetchObjectKeywordSet() throws Exception {
        List<Mission> existing = FetchObjectMissionCatalog.KEYWORDS.stream()
            .map(this::fetchMission)
            .toList();
        when(missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                fetchObject.getGameId(),
                FetchObjectMissionCatalog.MISSION_TYPE
            ))
            .thenReturn(existing);

        seeder.run(null);

        verify(missionRepository, never()).save(any());
    }

    @Test
    void seedsMissingCharadesTopicAndItsMissions() throws Exception {
        when(missionRepository
            .findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
                fetchObject.getGameId(),
                FetchObjectMissionCatalog.MISSION_TYPE
            ))
            .thenReturn(FetchObjectMissionCatalog.KEYWORDS.stream()
                .map(this::fetchMission)
                .toList());

        Game charades = game(3L, DevGameCatalogSeeder.CHARADES);
        CharadesMissionCatalog.TopicSpec instruments =
            CharadesMissionCatalog.TOPICS.stream()
                .filter(topic -> topic.name().equals("악기"))
                .findFirst()
                .orElseThrow();
        MissionTopic savedTopic = MissionTopic.builder()
            .topicId(99L)
            .game(charades)
            .name(instruments.name())
            .isActive(true)
            .build();
        when(missionTopicRepository.findByGameGameIdAndName(
            charades.getGameId(),
            instruments.name()
        )).thenReturn(Optional.empty());
        when(missionTopicRepository.save(any(MissionTopic.class)))
            .thenReturn(savedTopic);
        when(missionRepository
            .findAllByGameGameIdAndTopicTopicIdAndMissionType(
                charades.getGameId(),
                savedTopic.getTopicId(),
                DevGameCatalogSeeder.CHARADES
            ))
            .thenReturn(List.of());

        seeder.run(null);

        ArgumentCaptor<MissionTopic> topicCaptor =
            ArgumentCaptor.forClass(MissionTopic.class);
        verify(missionTopicRepository).save(topicCaptor.capture());
        assertThat(topicCaptor.getValue().getName())
            .isEqualTo(instruments.name());

        ArgumentCaptor<Mission> missionCaptor =
            ArgumentCaptor.forClass(Mission.class);
        verify(missionRepository, times(instruments.missions().size()))
            .save(missionCaptor.capture());
        assertThat(missionCaptor.getAllValues())
            .extracting(Mission::getKeyword)
            .containsExactlyElementsOf(instruments.missions().stream()
                .map(CharadesMissionCatalog.MissionSpec::keyword)
                .toList());
    }

    private Mission fetchMission(String keyword) {
        return Mission.builder()
            .game(fetchObject)
            .missionType(FetchObjectMissionCatalog.MISSION_TYPE)
            .keyword(keyword)
            .difficulty("NORMAL")
            .isActive(true)
            .build();
    }

    private static Mission charadesMission(
        Game game,
        MissionTopic topic,
        CharadesMissionCatalog.MissionSpec spec
    ) {
        return Mission.builder()
            .game(game)
            .topic(topic)
            .missionType(DevGameCatalogSeeder.CHARADES)
            .keyword(spec.keyword())
            .difficulty(spec.difficulty())
            .isActive(spec.active())
            .build();
    }

    private static Game game(Long id, String name) {
        return Game.builder()
            .gameId(id)
            .name(name)
            .description(name)
            .minPlayers(2)
            .maxPlayers(4)
            .minRounds(1)
            .maxRounds(10)
            .isActive(true)
            .build();
    }
}
