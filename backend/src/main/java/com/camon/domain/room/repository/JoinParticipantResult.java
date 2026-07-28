package com.camon.domain.room.repository;

public enum JoinParticipantResult {
    SUCCESS,
    ROOM_NOT_FOUND,
    ROOM_FULL,
    ROOM_ALREADY_STARTED,
    NICKNAME_DUPLICATED,
    ALREADY_JOINED,
    BANNED
}
