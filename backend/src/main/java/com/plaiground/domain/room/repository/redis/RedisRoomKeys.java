package com.plaiground.domain.room.repository.redis;

import java.util.UUID;

final class RedisRoomKeys {

    private static final String ROOM_PREFIX = "room:";
    private static final String ROOM_ID_PREFIX = "room:id:";
    private static final String HEARTBEAT_PREFIX = "session:";

    private RedisRoomKeys() {
    }

    static String room(String roomCode) {
        return ROOM_PREFIX + roomCode;
    }

    static String roomIdIndex(UUID roomId) {
        return ROOM_ID_PREFIX + roomId;
    }

    static String participants(String roomCode) {
        return room(roomCode) + ":participants";
    }

    static String participantPrefix(String roomCode) {
        return room(roomCode) + ":participant:";
    }

    static String participant(String roomCode, UUID participantId) {
        return participantPrefix(roomCode) + participantId;
    }

    static String nicknames(String roomCode) {
        return room(roomCode) + ":nicknames";
    }

    static String heartbeat(UUID participantId) {
        return HEARTBEAT_PREFIX + participantId + ":alive";
    }
}
