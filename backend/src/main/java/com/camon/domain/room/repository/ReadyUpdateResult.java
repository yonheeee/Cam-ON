package com.camon.domain.room.repository;

public record ReadyUpdateResult(
    ReadyUpdateStatus status,
    boolean ready,
    boolean allReady
) {
}
