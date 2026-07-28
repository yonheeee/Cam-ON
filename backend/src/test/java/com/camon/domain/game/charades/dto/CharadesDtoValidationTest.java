package com.camon.domain.game.charades.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CharadesDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidGuess() {
        assertThat(validator.validate(new CharadesGuessRequest("코끼리")))
            .isEmpty();
    }

    @Test
    void rejectsBlankGuess() {
        Set<ConstraintViolation<CharadesGuessRequest>> violations =
            validator.validate(new CharadesGuessRequest("   "));

        assertThat(violations)
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("text");
    }

    @Test
    void rejectsGuessLongerThanTwoHundredCharacters() {
        Set<ConstraintViolation<CharadesGuessRequest>> violations =
            validator.validate(new CharadesGuessRequest("가".repeat(201)));

        assertThat(violations)
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactly("text");
    }
}
