package com.camon.domain.game.fetch.repository;

final class FetchObjectRedisKeys {

    private FetchObjectRedisKeys() {
    }

    static String session(String roomCode, int sessionSeq) {
        return "room:%s:session:%d".formatted(roomCode, sessionSeq);
    }

    static String participants(String roomCode, int sessionSeq) {
        return session(roomCode, sessionSeq) + ":fetch:participants";
    }

    static String missionOrder(String roomCode, int sessionSeq) {
        return session(roomCode, sessionSeq) + ":fetch:mission_order";
    }

    static String round(String roomCode, int sessionSeq, int round) {
        return session(roomCode, sessionSeq) + ":fetch:round:" + round;
    }
}
