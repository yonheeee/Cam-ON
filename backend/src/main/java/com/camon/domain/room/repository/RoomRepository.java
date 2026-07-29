package com.camon.domain.room.repository;

import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.domain.Participant;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository {
    boolean saveIfAbsent(Room room);

    boolean tryCreate(Room room, Participant host);

    Optional<Room> findById(UUID roomId);

    Optional<Room> findByCode(String roomCode);

    void updateHost(UUID roomId, UUID hostParticipantId);

    void updateStatus(UUID roomId, RoomStatus status);

    // 코스에서 몇 번째 게임을 진행 중인지 기록한다. 게임 도메인이 이 값으로
    // room:{code}:session:{seq} 키를 조립하므로, 다음 게임으로 넘어갈 때 반드시 먼저 올려야 한다.
    void updateCurrentSessionSeq(UUID roomId, int sessionSeq);

    void delete(UUID roomId);
}
