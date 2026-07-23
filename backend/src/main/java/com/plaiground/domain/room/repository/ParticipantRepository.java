package com.plaiground.domain.room.repository;

import com.plaiground.domain.room.domain.ConnectionStatus;
import com.plaiground.domain.room.domain.Participant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository {
    JoinParticipantResult tryAdd(UUID roomId, Participant participant);

    Optional<Participant> findById(UUID roomId, UUID participantId);

    Optional<UUID> findCurrentRoomId(UUID participantId);

    List<Participant> findAll(UUID roomId);

    ReadyUpdateResult updateReady(
        UUID roomId,
        UUID participantId,
        boolean ready
    );

    void resetAllReady(UUID roomId);

    void updateConnectionStatus(
        UUID roomId,
        UUID participantId,
        ConnectionStatus status
    );

    void remove(UUID roomId, UUID participantId);

    LeaveRoomResult leave(UUID roomId, UUID participantId);

    LeaveRoomResult leaveIfHeartbeatExpired(
        UUID roomId,
        UUID participantId
    );
}
