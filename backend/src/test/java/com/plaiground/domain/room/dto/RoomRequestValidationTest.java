package com.plaiground.domain.room.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoomRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidCreateRoomRequest() {
        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(
            new CreateRoomRequest("즐거운 게임방", 4)
        );

        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsBlankTitleAndInvalidPlayerCount() {
        Set<ConstraintViolation<CreateRoomRequest>> violations = validator.validate(
            new CreateRoomRequest(" ", 5)
        );

        assertThat(violations)
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("title", "maxPlayers");
    }

    @Test
    void acceptsSixCharacterUppercaseRoomCode() {
        assertThat(validator.validate(new JoinRoomRequest("AB23CD"))).isEmpty();
    }

    @Test
    void rejectsInvalidRoomCode() {
        assertThat(validator.validate(new JoinRoomRequest("abc123")))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("roomCode");
    }

    @Test
    void requiresReadyValue() {
        assertThat(validator.validate(new UpdateReadyRequest(null)))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactly("ready");
    }
}
