package com.plaiground.domain.room.repository.redis;

import java.util.UUID;

final class RedisRoomKeys {

    private static final String ROOM_PREFIX = "room:";
    private static final String HEARTBEAT_PREFIX = "session:";

    private RedisRoomKeys() {
    }

    static String room(UUID roomId) {
        return ROOM_PREFIX + roomId;
    }

    static String participants(UUID roomId) {
        return room(roomId) + ":participants";
    }

    static String participantPrefix(UUID roomId) {
        return room(roomId) + ":participant:";
    }

    static String participant(UUID roomId, UUID participantId) {
        return participantPrefix(roomId) + participantId;
    }

    static String nicknames(UUID roomId) {
        return room(roomId) + ":nicknames";
    }

    static String heartbeat(UUID participantId) {
        return HEARTBEAT_PREFIX + participantId + ":alive";
    }
}
