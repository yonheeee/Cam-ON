package com.camon.domain.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.camon.domain.media.config.LiveKitProperties;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveKitTokenServiceTest {

    @Test
    void createsRoomJoinTokenForParticipant() throws Exception {
        LiveKitTokenService service = new LiveKitTokenService(
            new LiveKitProperties(
                "wss://example.livekit.cloud",
                "test-api-key",
                "test-secret-with-at-least-thirty-two-characters"
            )
        );
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        String token = service.createRoomJoinToken(
            roomId,
            participantId,
            "player"
        );

        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(participantId.toString());
        assertThat(claims.getStringClaim("name")).isEqualTo("player");
        assertThat(claims.getJSONObjectClaim("video"))
            .containsAllEntriesOf(Map.of(
                "roomJoin",
                true,
                "room",
                roomId.toString()
            ));
    }
}
