package com.camon.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.Mission;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.GameRepository;
import com.camon.domain.game.common.repository.MissionRepository;
import com.camon.domain.game.common.repository.MissionTopicRepository;
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
        // 몸으로 말해요 시드는 이 테스트의 대상이 아니므로 기존 주제가 있는 상태로 둔다.
        when(missionTopicRepository
            .findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(charades.getGameId()))
            .thenReturn(List.of(MissionTopic.builder()
                .topicId(1L)
                .game(charades)
                .name("동물")
                .isActive(true)
                .build()));

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
        verify(missionRepository, times(12)).save(captor.capture());
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
        verify(missionRepository, times(10)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(Mission::getKeyword)
            .containsExactlyElementsOf(
                FetchObjectMissionCatalog.KEYWORDS.subList(2, 12)
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

    private Mission fetchMission(String keyword) {
        return Mission.builder()
            .game(fetchObject)
            .missionType(FetchObjectMissionCatalog.MISSION_TYPE)
            .keyword(keyword)
            .difficulty("NORMAL")
            .isActive(true)
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
