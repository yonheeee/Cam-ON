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

    static String roundScores(String roomCode, int sessionSeq) {
        return state(roomCode, sessionSeq) + ":round_scores";
    }

    static String round(String roomCode, int sessionSeq, int round) {
        return session(roomCode, sessionSeq) + ":round:" + round;
    }

    private static String session(String roomCode, int sessionSeq) {
        return ROOM_PREFIX + roomCode + ":session:" + sessionSeq;
    }
}
