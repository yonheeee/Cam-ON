package com.plaiground.domain.room.service;

import com.plaiground.domain.room.config.RoomProperties;
import org.springframework.stereotype.Component;

@Component
public class RoomInviteLinkGenerator {

    private final String frontendBaseUrl;

    public RoomInviteLinkGenerator(RoomProperties properties) {
        this.frontendBaseUrl = removeTrailingSlash(properties.frontendBaseUrl());
    }

    public String generate(String roomCode) {
        return frontendBaseUrl + "/rooms/join?code=" + roomCode;
    }

    private static String removeTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
