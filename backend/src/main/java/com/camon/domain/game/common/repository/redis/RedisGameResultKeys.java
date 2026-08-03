package com.camon.domain.game.common.repository.redis;

import java.util.UUID;

final class RedisGameResultKeys {

    private static final String ROOM_PREFIX = "room:";

    private RedisGameResultKeys() {
    }

    static String room(UUID roomId) {
        return ROOM_PREFIX + roomId;
    }

    static String sessionPrefix(String roomCode) {
        return ROOM_PREFIX + roomCode + ":session:";
    }

    static String session(String roomCode, int sessionSeq) {
        return sessionPrefix(roomCode) + sessionSeq;
    }

    static String round(String roomCode, int sessionSeq, int round) {
        return session(roomCode, sessionSeq) + ":round:" + round;
    }

    static String roundResults(String roomCode, int sessionSeq, int round) {
        return round(roomCode, sessionSeq, round) + ":results";
    }

    static String sessionTotals(String roomCode, int sessionSeq) {
        return session(roomCode, sessionSeq) + ":totals";
    }

    static String courseTotals(String roomCode) {
        return ROOM_PREFIX + roomCode + ":course:totals";
    }

    static String courseContribution(String roomCode, int sessionSeq) {
        return session(roomCode, sessionSeq) + ":course-results";
    }
}
