package com.plaiground.dev;

import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.RoomRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// TEMP — room 도메인에 진짜(Redis 기반) RoomRepository 구현체가 아직 없어서 NinjaGameService가
// 뜨질 못한다. 고정된 테스트 방 하나만 들고 있는 인메모리 스텁. 빈 등록은 DevFixtureConfig에서
// @ConditionalOnMissingBean으로 하므로, 방/코스 도메인에 진짜 구현체가 생기면 자동으로 비활성화된다
// (방/코스 도메인이 완성되면 이 파일과 DevFixtureConfig, dev 패키지 전체를 지우면 됨).
public class DevRoomRepository implements RoomRepository {

    public static final UUID TEST_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String TEST_ROOM_CODE = "NINJATEST";

    private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();

    public DevRoomRepository() {
        rooms.put(TEST_ROOM_ID, new Room(
            TEST_ROOM_ID, TEST_ROOM_CODE, "닌자 테스트방",
            TEST_ROOM_ID, 4, RoomStatus.PLAYING, 1, Instant.now()
        ));
    }

    @Override
    public boolean saveIfAbsent(Room room) {
        return rooms.putIfAbsent(room.roomId(), room) == null;
    }

    @Override
    public Optional<Room> findById(UUID roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    @Override
    public Optional<Room> findByCode(String roomCode) {
        return rooms.values().stream().filter(room -> room.roomCode().equals(roomCode)).findFirst();
    }

    @Override
    public void updateHost(UUID roomId, UUID hostParticipantId) {
        rooms.computeIfPresent(roomId, (id, room) -> new Room(
            room.roomId(), room.roomCode(), room.title(), hostParticipantId,
            room.maxPlayers(), room.status(), room.currentSessionSeq(), room.createdAt()
        ));
    }

    @Override
    public void updateStatus(UUID roomId, RoomStatus status) {
        rooms.computeIfPresent(roomId, (id, room) -> new Room(
            room.roomId(), room.roomCode(), room.title(), room.hostParticipantId(),
            room.maxPlayers(), status, room.currentSessionSeq(), room.createdAt()
        ));
    }

    @Override
    public void delete(UUID roomId) {
        rooms.remove(roomId);
    }
}
