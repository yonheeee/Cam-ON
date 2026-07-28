package com.camon.domain.game.charades.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class CharadesAnswerMatcherTest {

    private final CharadesAnswerMatcher matcher =
        new CharadesAnswerMatcher();

    @Test
    void ignoresWhitespace() {
        assertThat(matcher.matches("아기 상어", "아기상어")).isTrue();
        assertThat(matcher.matches("아기\t상어\n", "아기상어")).isTrue();
    }

    @Test
    void requiresSpecialCharactersAndEmojiToMatch() {
        assertThat(matcher.matches("아기-상어!", "아기상어")).isFalse();
        assertThat(matcher.matches("(축구)", "축구")).isFalse();
        assertThat(matcher.matches("축구⚽", "축구")).isFalse();
        assertThat(matcher.matches("C++", "c++")).isTrue();
        assertThat(matcher.matches("축구⚽", "축구⚽")).isTrue();
    }

    @Test
    void ignoresEnglishLetterCase() {
        assertThat(matcher.matches("Baby Shark", "babyshark")).isTrue();
        assertThat(matcher.matches("CAM-ON", "cam-on")).isTrue();
        assertThat(matcher.matches("CAM-ON", "camon")).isFalse();
    }

    @Test
    void preservesLettersAndNumbersForExactComparison() {
        assertThat(matcher.normalize("Room 101!")).isEqualTo("room101!");
        assertThat(matcher.matches("Room 101", "room101")).isTrue();
        assertThat(matcher.matches("Room 102", "room101")).isFalse();
    }

    @Test
    void treatsCanonicallyEquivalentHangulAsEqual() {
        String decomposed = Normalizer.normalize(
            "코끼리",
            Normalizer.Form.NFD
        );

        assertThat(matcher.matches(decomposed, "코끼리")).isTrue();
    }

    @Test
    void doesNotAllowTyposOrSynonyms() {
        assertThat(matcher.matches("아기상어어", "아기상어")).isFalse();
        assertThat(matcher.matches("풋볼", "축구")).isFalse();
        assertThat(matcher.matches("코끼리", "기린")).isFalse();
    }

    @Test
    void rejectsNullAndBlankButKeepsVisibleSymbols() {
        assertThat(matcher.matches(null, "축구")).isFalse();
        assertThat(matcher.matches("축구", null)).isFalse();
        assertThat(matcher.matches("   ", "축구")).isFalse();
        assertThat(matcher.matches("!@#$", "!@#$")).isTrue();
    }

    @Test
    void removesInvisibleFormatAndControlCharacters() {
        assertThat(matcher.matches("아기\u200B상어", "아기상어")).isTrue();
        assertThat(matcher.matches("아기\u0000상어", "아기상어")).isTrue();
    }
}
