package com.camon.domain.game.charades.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CharadesMissionCatalog {

    private static final String RESOURCE_PATH =
        "game/charades-missions.csv";
    private static final int MIN_ACTIVE_MISSIONS_PER_TOPIC = 4;

    public static final List<TopicSpec> TOPICS = load();

    private CharadesMissionCatalog() {
    }

    private static List<TopicSpec> load() {
        InputStream input = CharadesMissionCatalog.class
            .getClassLoader()
            .getResourceAsStream(RESOURCE_PATH);
        if (input == null) {
            throw new IllegalStateException(
                "Charades mission catalog not found: " + RESOURCE_PATH
            );
        }

        Map<String, List<MissionSpec>> missionsByTopic =
            new LinkedHashMap<>();
        Set<String> uniqueMissions = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            String header = reader.readLine();
            if (!"topic,keyword,difficulty,is_active".equals(header)) {
                throw new IllegalStateException(
                    "Invalid charades mission catalog header"
                );
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length != 4) {
                    throw invalidLine(lineNumber, "expected 4 columns");
                }

                String topic = columns[0].trim();
                String keyword = columns[1].trim();
                String difficulty = columns[2].trim();
                boolean active = parseActive(columns[3].trim(), lineNumber);
                if (topic.isBlank() || keyword.isBlank()
                    || difficulty.isBlank()) {
                    throw invalidLine(lineNumber, "blank value");
                }
                if (!uniqueMissions.add(topic + "\u0000" + keyword)) {
                    throw invalidLine(
                        lineNumber,
                        "duplicate topic and keyword"
                    );
                }

                missionsByTopic.computeIfAbsent(
                    topic,
                    ignored -> new ArrayList<>()
                ).add(new MissionSpec(keyword, difficulty, active));
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read charades mission catalog",
                exception
            );
        }

        List<TopicSpec> topics = missionsByTopic.entrySet().stream()
            .map(entry -> new TopicSpec(
                entry.getKey(),
                List.copyOf(entry.getValue())
            ))
            .toList();
        topics.forEach(CharadesMissionCatalog::validateTopic);
        return List.copyOf(topics);
    }

    private static boolean parseActive(String value, int lineNumber) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw invalidLine(lineNumber, "is_active must be true or false");
    }

    private static void validateTopic(TopicSpec topic) {
        long activeCount = topic.missions().stream()
            .filter(MissionSpec::active)
            .count();
        if (activeCount < MIN_ACTIVE_MISSIONS_PER_TOPIC) {
            throw new IllegalStateException(
                "Charades topic '%s' requires at least %d active missions"
                    .formatted(
                        topic.name(),
                        MIN_ACTIVE_MISSIONS_PER_TOPIC
                    )
            );
        }
    }

    private static IllegalStateException invalidLine(
        int lineNumber,
        String reason
    ) {
        return new IllegalStateException(
            "Invalid charades mission catalog at line %d: %s"
                .formatted(lineNumber, reason)
        );
    }

    public record TopicSpec(
        String name,
        List<MissionSpec> missions
    ) {
    }

    public record MissionSpec(
        String keyword,
        String difficulty,
        boolean active
    ) {
    }
}
