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
