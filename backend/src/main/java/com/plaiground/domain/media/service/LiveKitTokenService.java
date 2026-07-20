package com.plaiground.domain.media.service;

import com.plaiground.domain.media.config.LiveKitProperties;
import org.springframework.stereotype.Service;

@Service
public class LiveKitTokenService {
    private final LiveKitProperties properties;

    public LiveKitTokenService(LiveKitProperties properties) {
        this.properties = properties;
    }

    // Token creation is added after the room/session contract is finalized.
}
