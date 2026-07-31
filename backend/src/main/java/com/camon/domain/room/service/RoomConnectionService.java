package com.camon.domain.room.service;

import com.camon.domain.room.config.RoomConnectionProperties;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.event.ParticipantLeftEvent;
import com.camon.domain.room.repository.ConnectionRepository;
import com.camon.domain.room.repository.HeartbeatRefreshResult;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.ws.RoomEventPublisher;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class RoomConnectionService {

    private record TimeoutTask(UUID timeoutId, ScheduledFuture<?> future) {
    }

    private final ConnectionRepository connectionRepository;
    private final ParticipantRepository participantRepository;
    private final RoomEventPublisher roomEventPublisher;
    private final RoomConnectionProperties properties;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConcurrentMap<UUID, TimeoutTask> timeoutTasks =
        new ConcurrentHashMap<>();
    // 참가자 1명이 STOMP 연결을 여러 개 연다(프론트의 로비/하트비트/게임시작 훅이 각자 연결한다).
    // 그래서 소켓 하나가 닫히는 건 "연결 끊김"이 아니다 — 예를 들어 게임이 시작돼 로비 화면이
    // 언마운트되면 소켓 하나가 정상적으로 닫힌다. 참가자별로 살아있는 세션 id를 들고 있다가
    // **마지막 하나가 닫힐 때만** 연결 끊김으로 처리한다.
    private final ConcurrentMap<UUID, Set<String>> sessionsByParticipant =
        new ConcurrentHashMap<>();
    // 끊김을 이미 알린 참가자들. 복귀(CONNECTED) 전파를 "정말 끊겼다고 알렸던 사람"에게만
    // 보내서, 최초 입장 시의 불필요한 이벤트를 만들지 않는다.
    private final Set<UUID> disconnectedParticipants = ConcurrentHashMap.newKeySet();

    public RoomConnectionService(
        ConnectionRepository connectionRepository,
        ParticipantRepository participantRepository,
        RoomEventPublisher roomEventPublisher,
        RoomConnectionProperties properties,
        @Qualifier("roomConnectionTaskScheduler") TaskScheduler taskScheduler,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.connectionRepository = connectionRepository;
        this.participantRepository = participantRepository;
        this.roomEventPublisher = roomEventPublisher;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void connected(UUID roomId, UUID participantId, String sessionId) {
        // 방/참가자 검증이 먼저다. 여기서 예외가 나면 CONNECT 자체가 거절되므로 세션을 등록하지 않는다.
        refreshHeartbeat(roomId, participantId);
        sessionsByParticipant
            .computeIfAbsent(participantId, key -> ConcurrentHashMap.newKeySet())
            .add(sessionId);
        if (disconnectedParticipants.remove(participantId)) {
            roomEventPublisher.publishMemberConnectionChanged(
                roomId,
                participantId,
                ConnectionStatus.CONNECTED
            );
        }
    }

    public void heartbeat(UUID roomId, UUID participantId) {
        refreshHeartbeat(roomId, participantId);
    }

    public void disconnected(UUID roomId, UUID participantId, String sessionId) {
        if (!unregisterSession(participantId, sessionId)) {
            // 아직 이 참가자의 다른 소켓이 살아 있다 — 끊긴 게 아니다.
            return;
        }
        if (participantRepository.findById(roomId, participantId).isEmpty()) {
            cancelTimeout(participantId);
            forget(participantId);
            return;
        }
        participantRepository.updateConnectionStatus(
            roomId,
            participantId,
            ConnectionStatus.DISCONNECTED
        );
        // 유예(heartbeatTtl) 동안 다른 참가자 화면에 "연결 끊김"으로 보이게 한다. 유예가 끝나면
        // handleTimeout이 member:left로 실제 퇴장 처리하고 방장이었으면 위임까지 한다.
        if (disconnectedParticipants.add(participantId)) {
            roomEventPublisher.publishMemberConnectionChanged(
                roomId,
                participantId,
                ConnectionStatus.DISCONNECTED
            );
        }
        scheduleTimeout(roomId, participantId);
    }

    /**
     * 방 참가자 중 하트비트가 이미 만료된 사람을 예약된 스윕을 기다리지 않고 즉시 퇴장 처리한다.
     *
     * <p>정원 판정이 participants 집합의 크기를 세기 때문에, 통보 없이 사라진 사람(창을 그냥 닫음,
     * 프로세스 강제 종료, 절전 등)의 자리가 TTL이 만료되고 스윕이 돌 때까지 방을 막는다. 그 사이
     * 입장 시도는 정원 초과로 거절되므로, 거절 직전에 이 메서드로 죽은 자리를 먼저 회수한다.
     * 하트비트가 살아 있는 참가자는 건드리지 않으므로 멀쩡한 사람이 밀려나지는 않는다.
     *
     * @return 실제로 퇴장 처리된 참가자 수
     */
    public int sweepExpired(UUID roomId) {
        int removed = 0;
        // 하트비트 키는 STOMP가 연결될 때 처음 생긴다. 즉 방금 join REST만 끝낸 참가자는 아직
        // 키가 없어서 "죽은 것"과 구분되지 않는다 — 예약 스윕(handleTimeout)은 연결된 뒤에만
        // 무장되므로 이 문제가 없지만, 참가자 전체를 훑는 이 스윕은 입장 중인 사람을 쫓아낼 수
        // 있다. 그래서 입장 후 TTL이 지나지 않은 참가자는 판단을 보류한다.
        Instant judgeableBefore = Instant.now().minus(properties.heartbeatTtl());
        for (Participant participant : participantRepository.findAll(roomId)) {
            UUID participantId = participant.participantId();
            if (connectionRepository.isAlive(participantId)) {
                continue;
            }
            if (participant.joinedAt().isAfter(judgeableBefore)) {
                continue;
            }
            if (removeIfExpired(roomId, participantId) == LeaveRoomStatus.SUCCESS) {
                // 이 참가자를 기다리던 예약 스윕은 더 볼 게 없다(남겨두면 PARTICIPANT_NOT_FOUND로
                // 한 번 깨어나기만 한다).
                cancelTimeout(participantId);
                removed++;
            }
        }
        return removed;
    }

    public void cancelTimeout(UUID participantId) {
        TimeoutTask task = timeoutTasks.remove(participantId);
        if (task != null) {
            task.future().cancel(false);
        }
    }

    /**
     * 세션을 하나 지우고, 그게 이 참가자의 마지막 세션이었는지 알려준다.
     * 등록된 적 없는 세션(예: CONNECT가 거절돼 등록 전에 닫힌 경우)은 false를 돌려준다 —
     * 추적 정보가 없다는 이유로 멀쩡한 참가자를 끊긴 것으로 오인하지 않기 위해서다.
     */
    private boolean unregisterSession(UUID participantId, String sessionId) {
        boolean[] lastSession = {false};
        sessionsByParticipant.computeIfPresent(participantId, (key, sessions) -> {
            if (!sessions.remove(sessionId) || !sessions.isEmpty()) {
                return sessions;
            }
            lastSession[0] = true;
            return null;
        });
        return lastSession[0];
    }

    private void forget(UUID participantId) {
        sessionsByParticipant.remove(participantId);
        disconnectedParticipants.remove(participantId);
    }

    /**
     * 하트비트가 만료된 참가자를 실제로 방에서 빼고 그 사실을 전파한다. 예약 스윕(handleTimeout)과
     * 입장 직전 즉시 스윕(sweepExpired)이 같은 처리를 해야 하므로 한곳에 모아둔다 — 갈라지면
     * 한쪽 경로에서만 방장 위임이나 member:left가 빠지는 식으로 어긋난다.
     *
     * @return Redis가 판정한 결과. HEARTBEAT_ACTIVE면 아직 살아 있어 아무것도 하지 않았다는 뜻이다.
     */
    private LeaveRoomStatus removeIfExpired(UUID roomId, UUID participantId) {
        LeaveRoomResult result =
            participantRepository.leaveIfHeartbeatExpired(
                roomId,
                participantId
            );
        if (result.status() != LeaveRoomStatus.SUCCESS) {
            return result.status();
        }
        UUID newHostParticipantId = result.hostChanged()
            ? result.newHostParticipantId()
            : null;
        roomEventPublisher.publishMemberLeft(
            roomId,
            participantId,
            newHostParticipantId,
            "TIMEOUT"
        );
        if (result.hostChanged()) {
            roomEventPublisher.publishHostChanged(
                roomId,
                result.previousHostParticipantId(),
                result.newHostParticipantId()
            );
        }
        // 방에서 실제로 빠졌으니 연결 추적 상태도 버린다. 남겨두면 같은 사람이 재입장했을 때
        // "끊겼다 돌아온 것"으로 오인해 불필요한 CONNECTED 이벤트가 나간다.
        forget(participantId);
        // 게임(charades 등) 리스너에게 이탈을 알린다 — 진행 중 게임의 턴/세션 정리용.
        applicationEventPublisher.publishEvent(
            new ParticipantLeftEvent(
                roomId,
                participantId,
                "TIMEOUT"
            )
        );
        return LeaveRoomStatus.SUCCESS;
    }

    private void refreshHeartbeat(UUID roomId, UUID participantId) {
        HeartbeatRefreshResult result = connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            properties.heartbeatTtl()
        );
        if (result == HeartbeatRefreshResult.ROOM_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (result == HeartbeatRefreshResult.PARTICIPANT_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_ACCESS_DENIED);
        }
        scheduleTimeout(roomId, participantId);
    }

    private void scheduleTimeout(UUID roomId, UUID participantId) {
        UUID timeoutId = UUID.randomUUID();
        timeoutTasks.compute(participantId, (key, currentTask) -> {
            if (currentTask != null) {
                currentTask.future().cancel(false);
            }
            ScheduledFuture<?> future = taskScheduler.schedule(
                () -> handleTimeout(roomId, participantId, timeoutId),
                Instant.now()
                    .plus(properties.heartbeatTtl())
                    .plusMillis(100)
            );
            return new TimeoutTask(timeoutId, future);
        });
    }

    private void handleTimeout(
        UUID roomId,
        UUID participantId,
        UUID timeoutId
    ) {
        TimeoutTask activeTask = timeoutTasks.get(participantId);
        if (activeTask == null
            || !activeTask.timeoutId().equals(timeoutId)) {
            return;
        }
        try {
            if (removeIfExpired(roomId, participantId)
                == LeaveRoomStatus.HEARTBEAT_ACTIVE) {
                // 아직 하트비트가 살아 있으면 이번 스윕은 건너뛴다. 다만 여기서 그냥 끝내면
                // 이 참가자는 다음 하트비트/끊김 이벤트가 오기 전까지 재검사 대상에서 빠지므로,
                // 방장이 이 상태로 조용히 사라지면 위임 자체가 영영 일어나지 않는다. 다시 무장한다.
                scheduleTimeout(roomId, participantId);
            }
        } finally {
            timeoutTasks.computeIfPresent(
                participantId,
                (key, currentTask) -> currentTask.timeoutId().equals(timeoutId)
                    ? null
                    : currentTask
            );
        }
    }
}
