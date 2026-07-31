package com.camon.domain.analytics.repository;

import com.camon.domain.analytics.domain.PlaytestSession;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaytestSessionRepository
    extends JpaRepository<PlaytestSession, UUID> {

    Optional<PlaytestSession> findFirstByRoomKeyOrderByAttemptNumberDesc(
        String roomKey
    );
}
