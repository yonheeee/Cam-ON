package com.camon.domain.analytics.repository;

import com.camon.domain.analytics.domain.PlaytestEvent;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaytestEventRepository extends JpaRepository<PlaytestEvent, UUID> {
    Optional<PlaytestEvent>
    findFirstByTestSessionIdAndParticipantKeyAndEventNameOrderByOccurredAtDesc(
        UUID testSessionId,
        String participantKey,
        String eventName
    );
}
