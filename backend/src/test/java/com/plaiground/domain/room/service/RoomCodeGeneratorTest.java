package com.plaiground.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoomCodeGeneratorTest {

    private final RoomCodeGenerator generator = new RoomCodeGenerator();

    @Test
    void generatesSixCharacterReadableCode() {
        String roomCode = generator.generate();

        assertThat(roomCode).matches("[A-HJ-NP-Z2-9]{6}");
    }

    @Test
    void avoidsAmbiguousCharacters() {
        for (int count = 0; count < 1_000; count++) {
            assertThat(generator.generate()).doesNotContain("I", "O", "0", "1");
        }
    }

    @Test
    void producesSufficientlyVariedCodes() {
        Set<String> generatedCodes = new HashSet<>();
        for (int count = 0; count < 1_000; count++) {
            generatedCodes.add(generator.generate());
        }

        assertThat(generatedCodes).hasSizeGreaterThan(990);
    }
}
