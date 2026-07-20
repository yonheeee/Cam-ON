package com.plaiground.domain.session.repository;

import com.plaiground.domain.session.domain.GuestSession;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    void save(GuestSession session);

    Optional<GuestSession> findByParticipantId(UUID participantId);

    void delete(UUID participantId);
}
