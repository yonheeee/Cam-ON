package com.camon.domain.game.charades.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CharadesMissionCatalogTest {

    @Test
    void loadsExpectedTopicsAndUniqueMissions() {
        assertThat(CharadesMissionCatalog.TOPICS)
            .extracting(CharadesMissionCatalog.TopicSpec::name)
            .containsExactly(
                "동물",
                "운동",
                "악기",
                "직업",
                "음식",
                "영화"
            );
        assertThat(CharadesMissionCatalog.TOPICS)
            .allSatisfy(topic -> {
                List<String> keywords = topic.missions().stream()
                    .map(CharadesMissionCatalog.MissionSpec::keyword)
                    .toList();
                assertThat(keywords).doesNotHaveDuplicates();
                assertThat(topic.missions().stream()
                    .filter(CharadesMissionCatalog.MissionSpec::active))
                    .hasSizeGreaterThanOrEqualTo(4);
            });
        assertThat(CharadesMissionCatalog.TOPICS)
            .flatExtracting(CharadesMissionCatalog.TopicSpec::missions)
            .hasSize(94);
    }

    @Test
    void removesDuplicateKangarooAndSeparatesMovieKeywords() {
        CharadesMissionCatalog.TopicSpec animals = topic("동물");
        CharadesMissionCatalog.TopicSpec movies = topic("영화");

        assertThat(animals.missions())
            .extracting(CharadesMissionCatalog.MissionSpec::keyword)
            .containsOnlyOnce("캥거루");
        assertThat(movies.missions())
            .extracting(CharadesMissionCatalog.MissionSpec::keyword)
            .contains("E.T.", "해리포터")
            .doesNotContain("E.T. 해리포터");
    }

    private static CharadesMissionCatalog.TopicSpec topic(String name) {
        return CharadesMissionCatalog.TOPICS.stream()
            .filter(topic -> topic.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
