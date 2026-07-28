package com.camon.domain.room.repository;

// 검증(방 존재/WAITING/방장 여부/자기 자신/대상 존재)과 제거를 한 Lua 스크립트에서 원자적으로
// 처리하고, 그 결과를 서비스가 에러 코드로 번역한다. 검증을 서비스에서 따로 하면 조회와 제거
// 사이에 방장 위임/퇴장이 끼어들 수 있다.
public enum KickParticipantResult {
    SUCCESS,
    ROOM_NOT_FOUND,
    ROOM_ALREADY_STARTED,
    NOT_HOST,
    SELF_KICK,
    PARTICIPANT_NOT_FOUND
}
