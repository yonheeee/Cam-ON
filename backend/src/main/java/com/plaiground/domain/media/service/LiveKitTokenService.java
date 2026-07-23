package com.plaiground.domain.media.service;

import com.plaiground.domain.media.config.LiveKitProperties;
import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LiveKitTokenService {
    private final LiveKitProperties properties;

    public LiveKitTokenService(LiveKitProperties properties) {
        this.properties = properties;
    }

    // Token creation is added after the room/session contract is finalized.
    public String createRoomJoinToken(
        UUID roomId,
        UUID participantId,
        String nickname
    ) {
        AccessToken token = new AccessToken(
            properties.apiKey(),
            properties.apiSecret()
        );
        token.setIdentity(participantId.toString());
        token.setName(nickname);
        token.addGrants(
            new RoomJoin(true),
            new RoomName(roomId.toString())
        );
        return token.toJwt();
    }
}
