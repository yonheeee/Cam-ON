package com.camon.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 방입니다."),
    ROOM_FULL(HttpStatus.CONFLICT, "방 정원이 가득 찼습니다."),
    ROOM_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작한 방입니다."),
    ROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "방 참가자만 조회할 수 있습니다."),
    // 게임 시작 외에 강퇴 등 방장 전용 작업이 늘어나서 메시지를 작업 중립적으로 일반화했다.
    ROOM_NOT_HOST(HttpStatus.FORBIDDEN, "방장만 할 수 있는 작업입니다."),
    ROOM_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "방에 없는 참가자입니다."),
    ROOM_KICK_SELF(HttpStatus.BAD_REQUEST, "자기 자신은 강퇴할 수 없습니다."),
    ROOM_BANNED(HttpStatus.FORBIDDEN, "강퇴된 방에는 다시 입장할 수 없습니다."),
    ROOM_NOT_ALL_READY(HttpStatus.CONFLICT, "모든 참가자가 준비되어야 게임을 시작할 수 있습니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참가한 방입니다."),
    ROOM_CODE_GENERATION_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "방 코드를 생성하지 못했습니다."
    ),
    NINJA_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 닌자 게임 세션이 없습니다."),
    NINJA_ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 라운드입니다."),
    NINJA_STALE_ROUND(HttpStatus.CONFLICT, "이미 지난 라운드입니다."),
    NINJA_ROUND_CLOSED(HttpStatus.CONFLICT, "이미 종료된 라운드입니다."),
    NINJA_NOT_ALIVE(HttpStatus.CONFLICT, "이미 탈락한 참가자입니다."),
    NINJA_WRONG_SKILL(HttpStatus.CONFLICT, "이번 라운드에 요구되는 손동작이 아닙니다."),
    NINJA_ALREADY_CLAIMED(HttpStatus.CONFLICT, "이미 다른 참가자가 공격권을 획득했습니다."),
    NINJA_NOT_ATTACKER(HttpStatus.FORBIDDEN, "이번 라운드의 공격권을 획득한 참가자가 아닙니다."),
    NINJA_TARGET_ALREADY_SET(HttpStatus.CONFLICT, "이미 공격 대상이 지정되었습니다."),
    NINJA_INVALID_TARGET(HttpStatus.BAD_REQUEST, "공격 대상으로 지정할 수 없는 참가자입니다."),
    NINJA_NOT_ENOUGH_PLAYERS(HttpStatus.BAD_REQUEST, "닌자 게임은 최소 2명 이상이어야 시작할 수 있습니다."),
    NINJA_TOO_MANY_PLAYERS(HttpStatus.BAD_REQUEST, "닌자 게임은 최대 4명까지만 참가할 수 있습니다."),
    CHARADES_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 몸으로 말해요 게임 세션이 없습니다."),
    CHARADES_NOT_ENOUGH_PLAYERS(HttpStatus.BAD_REQUEST, "몸으로 말해요는 최소 3명 이상이어야 시작할 수 있습니다."),
    CHARADES_TOO_MANY_PLAYERS(HttpStatus.BAD_REQUEST, "몸으로 말해요는 최대 4명까지만 참가할 수 있습니다."),
    CHARADES_TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND, "선택할 수 없는 몸으로 말해요 주제입니다."),
    CHARADES_NOT_ENOUGH_MISSIONS(HttpStatus.CONFLICT, "선택한 주제에 게임 진행에 필요한 제시어가 부족합니다."),
    CHARADES_TURN_STILL_PLAYING(HttpStatus.CONFLICT, "현재 표현 턴이 아직 진행 중입니다."),
    CHARADES_TURN_NOT_PLAYING(HttpStatus.CONFLICT, "현재 진행 중인 몸으로 말해요 표현 턴이 없습니다."),
    CHARADES_TURN_EXPIRED(HttpStatus.CONFLICT, "현재 몸으로 말해요 표현 턴의 제한시간이 지났습니다."),
    CHARADES_NOT_PRESENTER(HttpStatus.FORBIDDEN, "현재 표현자만 제시어를 조회할 수 있습니다."),
    CHARADES_PRESENTER_CANNOT_GUESS(HttpStatus.FORBIDDEN, "현재 표현자는 정답을 제출할 수 없습니다."),
    CHARADES_WORD_NOT_FOUND(HttpStatus.NOT_FOUND, "현재 턴의 제시어를 찾을 수 없습니다."),
    GAME_NOT_CURRENT(HttpStatus.CONFLICT, "현재 진행 중인 게임이 아닙니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
