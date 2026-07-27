package com.camon.domain.room.service;

import com.camon.domain.room.config.RoomConnectionProperties;
import com.camon.domain.room.domain.ConnectionStatus;
import com.camon.domain.room.event.ParticipantForcedLeftEvent;
import com.camon.domain.room.repository.ConnectionRepository;
import com.camon.domain.room.repository.HeartbeatRefreshResult;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.ws.RoomEventPublisher;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Instant;
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

    public void connected(UUID roomId, UUID participantId) {
        refreshHeartbeat(roomId, participantId);
    }

    public void heartbeat(UUID roomId, UUID participantId) {
        refreshHeartbeat(roomId, participantId);
    }

    public void disconnected(UUID roomId, UUID participantId) {
        if (participantRepository.findById(roomId, participantId).isEmpty()) {
            cancelTimeout(participantId);
            return;
        }
        participantRepository.updateConnectionStatus(
            roomId,
            participantId,
            ConnectionStatus.DISCONNECTED
        );
        scheduleTimeout(roomId, participantId);
    }

    public void cancelTimeout(UUID participantId) {
        TimeoutTask task = timeoutTasks.remove(participantId);
        if (task != null) {
            task.future().cancel(false);
        }
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
            applicationEventPublisher.publishEvent(
                new ParticipantForcedLeftEvent(
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
