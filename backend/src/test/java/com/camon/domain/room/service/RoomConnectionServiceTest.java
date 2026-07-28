package com.camon.domain.room.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.camon.domain.room.config.RoomConnectionProperties;
import com.camon.domain.room.repository.ConnectionRepository;
import com.camon.domain.room.repository.HeartbeatRefreshResult;
import com.camon.domain.room.repository.LeaveRoomResult;
import com.camon.domain.room.repository.LeaveRoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.ws.RoomEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

class RoomConnectionServiceTest {

    @Test
    void removesParticipantAndPublishesTimeoutAfterHeartbeatExpires() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID newHostId = UUID.randomUUID();
        when(connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        )).thenReturn(HeartbeatRefreshResult.SUCCESS);
        doReturn(future).when(scheduler).schedule(
            task.capture(),
            any(Instant.class)
        );
        when(participantRepository.leaveIfHeartbeatExpired(
            roomId,
            participantId
        )).thenReturn(new LeaveRoomResult(
            LeaveRoomStatus.SUCCESS,
            participantId,
            participantId,
            newHostId,
            false
        ));
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            scheduler
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();

        verify(publisher).publishMemberLeft(
            roomId,
            participantId,
            newHostId,
            "TIMEOUT"
        );
        verify(publisher).publishHostChanged(roomId, participantId, newHostId);
        verify(scheduler).schedule(eq(task.getValue()), any(Instant.class));
    }

    @Test
    void rearmsTimeoutWhenHeartbeatIsStillActive() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        )).thenReturn(HeartbeatRefreshResult.SUCCESS);
        doReturn(future).when(scheduler).schedule(
            task.capture(),
            any(Instant.class)
        );
        when(participantRepository.leaveIfHeartbeatExpired(
            roomId,
            participantId
        )).thenReturn(new LeaveRoomResult(
            LeaveRoomStatus.HEARTBEAT_ACTIVE,
            participantId,
            null,
            null,
            false
        ));
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            scheduler
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();

        // 하트비트가 살아 있어 이번엔 제거하지 않았지만, 다시 무장해 두지 않으면 이 참가자는
        // (방장이라면 위임까지) 영영 재검사되지 않는다.
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void removesTimedOutParticipantWithoutHostChangeWhenLeaverIsNotHost() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        )).thenReturn(HeartbeatRefreshResult.SUCCESS);
        doReturn(future).when(scheduler).schedule(
            task.capture(),
            any(Instant.class)
        );
        when(participantRepository.leaveIfHeartbeatExpired(
            roomId,
            participantId
        )).thenReturn(new LeaveRoomResult(
            LeaveRoomStatus.SUCCESS,
            participantId,
            hostId,
            hostId,
            false
        ));
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            scheduler
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();

        verify(publisher).publishMemberLeft(roomId, participantId, null, "TIMEOUT");
        verify(publisher, never()).publishHostChanged(
            any(UUID.class),
            any(UUID.class),
            any(UUID.class)
        );
    }
}
