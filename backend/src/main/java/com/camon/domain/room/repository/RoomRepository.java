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

    /**
     * 현재 seq가 {@code fromSeq}일 때만 {@code toSeq}로 올린다(compare-and-set).
     *
     * <p>인터미션 타이머와 방장의 "바로 시작"이 동시에 다음 게임을 열려고 할 수 있어서 필요하다.
     * 읽고-쓰는 두 단계로 하면 둘 다 "아직 내 차례"라고 판단해 세션을 두 번 여는 창이 생긴다.
     * 진행 권한을 얻은 쪽만 true를 받는다.
     *
     * @return seq를 올린 호출자에게만 true
     */
    boolean tryAdvanceSessionSeq(UUID roomId, int fromSeq, int toSeq);

    void delete(UUID roomId);
}
