package com.camon.domain.room.service;

import com.camon.domain.room.config.RoomConnectionProperties;
import com.camon.domain.room.domain.ConnectionStatus;
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
            LeaveRoomResult result =
                participantRepository.leaveIfHeartbeatExpired(
                    roomId,
                    participantId
                );
            if (result.status() == LeaveRoomStatus.HEARTBEAT_ACTIVE) {
                // 아직 하트비트가 살아 있으면 이번 스윕은 건너뛴다. 다만 여기서 그냥 끝내면
                // 이 참가자는 다음 하트비트/끊김 이벤트가 오기 전까지 재검사 대상에서 빠지므로,
                // 방장이 이 상태로 조용히 사라지면 위임 자체가 영영 일어나지 않는다. 다시 무장한다.
                scheduleTimeout(roomId, participantId);
                return;
            }
            if (result.status() != LeaveRoomStatus.SUCCESS) {
                return;
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
