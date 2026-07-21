package com.plaiground.domain.room.repository;

import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository {
    boolean saveIfAbsent(Room room);

    Optional<Room> findById(UUID roomId);

    void updateHost(UUID roomId, UUID hostParticipantId);

    void updateStatus(UUID roomId, RoomStatus status);

    void delete(UUID roomId);
}
