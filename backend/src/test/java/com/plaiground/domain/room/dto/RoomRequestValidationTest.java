package com.plaiground.domain.room.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
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
    void acceptsRoomId() {
        assertThat(validator.validate(new JoinRoomRequest(UUID.randomUUID())))
            .isEmpty();
    }

    @Test
    void rejectsNullRoomId() {
        assertThat(validator.validate(new JoinRoomRequest(null)))
            .extracting(violation -> violation.getPropertyPath().toString())
            .contains("roomId");
    }

    @Test
    void requiresReadyValue() {
        assertThat(validator.validate(new UpdateReadyRequest(null)))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsExactly("ready");
    }
}
