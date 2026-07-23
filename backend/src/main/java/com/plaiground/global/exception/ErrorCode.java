package com.plaiground.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 방입니다."),
    ROOM_FULL(HttpStatus.CONFLICT, "방 정원이 가득 찼습니다."),
    ROOM_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작한 방입니다."),
    ROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "방 참가자만 조회할 수 있습니다."),
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
