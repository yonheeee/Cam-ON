package com.camon.domain.course.repository.redis;

import java.util.UUID;

final class RedisCourseKeys {

    private static final String ROOM_PREFIX = "room:";

    private RedisCourseKeys() {
    }

    // 방 해시는 roomId로 키를 잡는다(domain/room이 그렇게 저장한다) — course_length가 여기 살고,
    // 코스 항목 자체는 아래처럼 roomCode를 쓴다. 두 네임스페이스가 섞여 보이지만, 게임 도메인이
    // 이미 room:{code}:session:{seq} / room:{code}:course:totals를 roomCode로 쓰고 있어 그쪽에 맞췄다.
    static String room(UUID roomId) {
        return ROOM_PREFIX + roomId;
    }

    // "room:{code}:course:" — 뒤에 idx(1부터)를 붙여 코스 한 칸의 해시 키가 된다.
    static String coursePrefix(String roomCode) {
        return ROOM_PREFIX + roomCode + ":course:";
    }

    // 진행 중인 게임 세션. 게임 도메인들도 같은 키를 쓰므로(닌자가 여기에 자기 진행 상태를 얹는다)
    // 키 모양이 바뀌면 게임 쪽과 함께 깨진다.
    static String session(String roomCode, int sessionSeq) {
        return ROOM_PREFIX + roomCode + ":session:" + sessionSeq;
    }
}
