package com.camon.domain.room.repository;

import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
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

    /**
     * 방장이 대기방에서 참가자를 강퇴한다. 검증(방장 여부/WAITING/대상 존재)과 제거·재입장
     * 차단(banned) 등록까지 원자적으로 수행한다.
     */
    KickParticipantResult kick(UUID roomId, UUID requesterId, UUID targetId);

    LeaveRoomResult leaveIfHeartbeatExpired(
        UUID roomId,
        UUID participantId
    );
}
