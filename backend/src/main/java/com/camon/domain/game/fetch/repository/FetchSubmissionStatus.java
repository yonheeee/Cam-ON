package com.camon.domain.game.fetch.repository;

public enum FetchSubmissionStatus {
    SUCCESS,
    SESSION_NOT_FOUND,
    ROUND_NOT_FOUND,
    STALE_ROUND,
    ROUND_CLOSED,
    COUNTDOWN_ACTIVE,
    ROUND_EXPIRED,
    PARTICIPANT_NOT_FOUND,
    ALREADY_SUBMITTED
}
