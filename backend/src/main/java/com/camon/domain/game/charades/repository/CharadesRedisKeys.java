package com.camon.domain.game.charades.repository;

final class CharadesRedisKeys {

    private static final String ROOM_PREFIX = "room:";

    private CharadesRedisKeys() {
    }

    static String state(String roomCode, int sessionSeq) {
        return session(roomCode, sessionSeq) + ":charades";
    }

    static String presenterOrder(String roomCode, int sessionSeq) {
        return state(roomCode, sessionSeq) + ":presenter_order";
    }

    static String usedMissions(String roomCode, int sessionSeq) {
        return state(roomCode, sessionSeq) + ":used_missions";
    }

    private static String session(String roomCode, int sessionSeq) {
        return ROOM_PREFIX + roomCode + ":session:" + sessionSeq;
    }
}
