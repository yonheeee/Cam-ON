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
        // 제시어를 늘리거나 줄이면 이 숫자도 같이 고친다. 일부러 하드코딩한 canary다 —
        // csv를 편집하다 무관한 줄이 딸려 지워지는 걸 잡으라고 둔 것이고, 실제로 제시어 확충
        // 커밋에서 알파카/E.T.가 조용히 사라진 걸 이 assert가 잡았다.
        assertThat(CharadesMissionCatalog.TOPICS)
            .flatExtracting(CharadesMissionCatalog.TopicSpec::missions)
            .hasSize(226); // 166 + 사자성어 30 + 속담 30 (2026-08-04)
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
