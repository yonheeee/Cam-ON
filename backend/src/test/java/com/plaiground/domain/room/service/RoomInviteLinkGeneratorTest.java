package com.plaiground.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaiground.domain.room.config.RoomProperties;
import org.junit.jupiter.api.Test;

class RoomInviteLinkGeneratorTest {

    @Test
    void createsInviteLinkWithoutDuplicateSlash() {
        RoomInviteLinkGenerator generator = new RoomInviteLinkGenerator(
            new RoomProperties("https://plaiground.example/")
        );

        assertThat(generator.generate("AB23CD")).isEqualTo(
            "https://plaiground.example/rooms/join?code=AB23CD"
        );
    }
}
