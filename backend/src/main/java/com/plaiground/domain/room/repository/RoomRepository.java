package com.plaiground.domain.room.repository;

import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.domain.Participant;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository {
    boolean saveIfAbsent(Room room);

    boolean tryCreate(Room room, Participant host);

    Optional<Room> findById(UUID roomId);

    Optional<Room> findByCode(String roomCode);

    LeaveRoomResult leave(UUID roomId, UUID participantId);

    void updateHost(UUID roomId, UUID hostParticipantId);

    void updateStatus(UUID roomId, RoomStatus status);

    void delete(UUID roomId);
}
