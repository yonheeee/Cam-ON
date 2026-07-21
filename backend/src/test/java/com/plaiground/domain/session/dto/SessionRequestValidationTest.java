package com.plaiground.domain.session.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SessionRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidNickname() {
        assertThat(validator.validate(new CreateSessionRequest("플레이어"))).isEmpty();
        assertThat(validator.validate(new CreateSessionRequest("Player12"))).isEmpty();
    }

    @Test
    void rejectsBlankNickname() {
        assertThat(validator.validate(new CreateSessionRequest(" ")))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("nickname");
    }

    @Test
    void rejectsNicknameLongerThanEightCharacters() {
        String nickname = "가".repeat(9);

        assertThat(validator.validate(new CreateSessionRequest(nickname)))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("nickname");
    }

    @Test
    void rejectsWhitespaceInNickname() {
        assertThat(validator.validate(new CreateSessionRequest("플레이 어")))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("nickname");
    }

    @Test
    void rejectsSpecialCharactersInNickname() {
        assertThat(validator.validate(new CreateSessionRequest("플레이어!")))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("nickname");
    }

    @Test
    void rejectsEmojiInNickname() {
        assertThat(validator.validate(new CreateSessionRequest("플레이어🎮")))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("nickname");
    }
}
