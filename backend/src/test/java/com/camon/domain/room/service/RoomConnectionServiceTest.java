package com.camon.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

class RoomConnectionServiceTest {

    // 프론트가 참가자당 STOMP 연결을 여러 개(로비/하트비트/게임시작) 열기 때문에, 연결 끊김
    // 판정은 "소켓 하나가 닫혔나"가 아니라 "마지막 소켓이 닫혔나"여야 한다. 아래 픽스처는
    // 그 시나리오를 세션 id 단위로 재현하기 위한 것.
    private record Fixture(
        RoomConnectionService service,
        ParticipantRepository participantRepository,
        RoomEventPublisher publisher,
        UUID roomId,
        UUID participantId
    ) {
    }

    private static Fixture connectedFixture() {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        ParticipantRepository participantRepository = mock(ParticipantRepository.class);
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        when(connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        )).thenReturn(HeartbeatRefreshResult.SUCCESS);
        doReturn(future).when(scheduler).schedule(
            any(Runnable.class),
            any(Instant.class)
        );
        when(participantRepository.findById(roomId, participantId))
            .thenReturn(Optional.of(new Participant(
                participantId,
                "참가자",
                false,
                ConnectionStatus.CONNECTED,
                Instant.parse("2026-07-28T00:00:00Z")
            )));

        return new Fixture(
            new RoomConnectionService(
                connectionRepository,
                participantRepository,
                publisher,
                new RoomConnectionProperties(Duration.ofSeconds(15)),
                scheduler,
                mock(ApplicationEventPublisher.class)
            ),
            participantRepository,
            publisher,
            roomId,
            participantId
        );
    }

    @Test
    void keepsParticipantConnectedWhenOnlyOneOfSeveralSocketsCloses() {
        Fixture f = connectedFixture();
        f.service().connected(f.roomId(), f.participantId(), "lobby-socket");
        f.service().connected(f.roomId(), f.participantId(), "heartbeat-socket");

        f.service().disconnected(f.roomId(), f.participantId(), "lobby-socket");

        // 하트비트 소켓이 살아 있으므로 끊김이 아니다 — 상태 변경도, 이벤트도 없어야 한다.
        verify(f.participantRepository(), never()).updateConnectionStatus(
            any(UUID.class),
            any(UUID.class),
            any(ConnectionStatus.class)
        );
        verify(f.publisher(), never()).publishMemberConnectionChanged(
            any(UUID.class),
            any(UUID.class),
            any(ConnectionStatus.class)
        );
    }

    @Test
    void marksDisconnectedOnlyWhenLastSocketCloses() {
        Fixture f = connectedFixture();
        f.service().connected(f.roomId(), f.participantId(), "lobby-socket");
        f.service().connected(f.roomId(), f.participantId(), "heartbeat-socket");

        f.service().disconnected(f.roomId(), f.participantId(), "lobby-socket");
        f.service().disconnected(f.roomId(), f.participantId(), "heartbeat-socket");

        verify(f.participantRepository()).updateConnectionStatus(
            f.roomId(),
            f.participantId(),
            ConnectionStatus.DISCONNECTED
        );
        verify(f.publisher()).publishMemberConnectionChanged(
            f.roomId(),
            f.participantId(),
            ConnectionStatus.DISCONNECTED
        );
    }

    @Test
    void publishesConnectedWhenParticipantReturnsWithinGrace() {
        Fixture f = connectedFixture();
        f.service().connected(f.roomId(), f.participantId(), "socket-1");
        f.service().disconnected(f.roomId(), f.participantId(), "socket-1");

        f.service().connected(f.roomId(), f.participantId(), "socket-2");

        verify(f.publisher()).publishMemberConnectionChanged(
            f.roomId(),
            f.participantId(),
            ConnectionStatus.CONNECTED
        );
    }

    @Test
    void doesNotPublishConnectionEventOnFirstConnect() {
        Fixture f = connectedFixture();

        f.service().connected(f.roomId(), f.participantId(), "socket-1");

        // 최초 입장은 member:joined가 이미 알린다 — 여기서 CONNECTED까지 쏘면 중복 잡음.
        verify(f.publisher(), never()).publishMemberConnectionChanged(
            any(UUID.class),
            any(UUID.class),
            any(ConnectionStatus.class)
        );
    }

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
        ApplicationEventPublisher applicationEventPublisher =
            mock(ApplicationEventPublisher.class);
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
            scheduler,
            applicationEventPublisher
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();
        task.getValue().run();

        verify(publisher).publishMemberLeft(
            roomId,
            participantId,
            newHostId,
            "TIMEOUT"
        );
        verify(publisher).publishHostChanged(
            roomId,
            participantId,
            newHostId
        );
        verify(applicationEventPublisher).publishEvent(
            new ParticipantLeftEvent(roomId, participantId, "TIMEOUT")
        );
        verify(participantRepository, times(1))
            .leaveIfHeartbeatExpired(roomId, participantId);
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
        ApplicationEventPublisher applicationEventPublisher =
            mock(ApplicationEventPublisher.class);
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
            scheduler,
            applicationEventPublisher
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();

        verify(scheduler, times(2)).schedule(
            any(Runnable.class),
            any(Instant.class)
        );
        verifyNoInteractions(publisher);
        verify(applicationEventPublisher, never()).publishEvent(any());
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
        ApplicationEventPublisher applicationEventPublisher =
            mock(ApplicationEventPublisher.class);
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
            scheduler,
            applicationEventPublisher
        );

        service.heartbeat(roomId, participantId);
        task.getValue().run();

        verify(publisher).publishMemberLeft(
            roomId,
            participantId,
            null,
            "TIMEOUT"
        );
        verify(publisher, never()).publishHostChanged(
            any(UUID.class),
            any(UUID.class),
            any(UUID.class)
        );
        verify(applicationEventPublisher).publishEvent(
            new ParticipantLeftEvent(roomId, participantId, "TIMEOUT")
        );
    }

    // 창을 그냥 닫은 사람의 자리는 TTL이 만료돼도 예약 스윕이 돌기 전까지 방을 막는다.
    // sweepExpired는 그 자리만 골라 즉시 회수하고, 하트비트가 살아 있는 사람은 건드리지 않는다.
    @Test
    void sweepRemovesOnlyParticipantsWhoseHeartbeatExpired() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ApplicationEventPublisher applicationEventPublisher =
            mock(ApplicationEventPublisher.class);
        UUID roomId = UUID.randomUUID();
        UUID aliveId = UUID.randomUUID();
        UUID goneId = UUID.randomUUID();
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            // 입장 유예(TTL 15초)를 넘긴 시점이어야 스윕 판단 대상이 된다.
            new Participant(
                aliveId,
                "남은사람",
                false,
                ConnectionStatus.CONNECTED,
                Instant.now().minusSeconds(60)
            ),
            new Participant(
                goneId,
                "창닫은사람",
                false,
                ConnectionStatus.DISCONNECTED,
                Instant.now().minusSeconds(59)
            )
        ));
        when(connectionRepository.isAlive(aliveId)).thenReturn(true);
        when(connectionRepository.isAlive(goneId)).thenReturn(false);
        when(participantRepository.leaveIfHeartbeatExpired(roomId, goneId))
            .thenReturn(new LeaveRoomResult(
                LeaveRoomStatus.SUCCESS,
                goneId,
                aliveId,
                aliveId,
                false
            ));
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            scheduler,
            applicationEventPublisher
        );

        int removed = service.sweepExpired(roomId);

        assertThat(removed).isEqualTo(1);
        verify(participantRepository).leaveIfHeartbeatExpired(roomId, goneId);
        verify(participantRepository, never())
            .leaveIfHeartbeatExpired(roomId, aliveId);
        verify(publisher).publishMemberLeft(roomId, goneId, null, "TIMEOUT");
        verify(applicationEventPublisher).publishEvent(
            new ParticipantLeftEvent(roomId, goneId, "TIMEOUT")
        );
    }

    // 하트비트 키는 STOMP 연결 시점에 생기므로, join REST만 끝낸 참가자는 아직 키가 없다.
    // 이 사람을 죽은 것으로 보고 치우면 "입장하는 중에 쫓겨나는" 일이 생긴다.
    @Test
    void sweepSparesParticipantWhoJustJoinedAndHasNotConnectedYet() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        UUID roomId = UUID.randomUUID();
        UUID joiningId = UUID.randomUUID();
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            new Participant(
                joiningId,
                "입장중",
                false,
                ConnectionStatus.CONNECTED,
                // 방금 입장 — 아직 하트비트를 보낼 기회가 없었다.
                Instant.now()
            )
        ));
        when(connectionRepository.isAlive(joiningId)).thenReturn(false);
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            mock(TaskScheduler.class),
            mock(ApplicationEventPublisher.class)
        );

        int removed = service.sweepExpired(roomId);

        assertThat(removed).isZero();
        verify(participantRepository, never())
            .leaveIfHeartbeatExpired(any(UUID.class), any(UUID.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void sweepRemovesNothingWhenEveryoneIsAlive() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findAll(roomId)).thenReturn(List.of(
            new Participant(
                participantId,
                "참가자",
                false,
                ConnectionStatus.CONNECTED,
                Instant.now().minusSeconds(60)
            )
        ));
        when(connectionRepository.isAlive(participantId)).thenReturn(true);
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            mock(TaskScheduler.class),
            mock(ApplicationEventPublisher.class)
        );

        int removed = service.sweepExpired(roomId);

        assertThat(removed).isZero();
        verify(participantRepository, never())
            .leaveIfHeartbeatExpired(any(UUID.class), any(UUID.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void reconnectWithinGracePeriodKeepsParticipantAndIgnoresOldTimeout() {
        ConnectionRepository connectionRepository = mock(
            ConnectionRepository.class
        );
        ParticipantRepository participantRepository = mock(
            ParticipantRepository.class
        );
        RoomEventPublisher publisher = mock(RoomEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ApplicationEventPublisher applicationEventPublisher =
            mock(ApplicationEventPublisher.class);
        ScheduledFuture<?> disconnectedFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> reconnectedFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tasks =
            ArgumentCaptor.forClass(Runnable.class);
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Participant participant = new Participant(
            participantId,
            "presenter",
            true,
            ConnectionStatus.CONNECTED,
            Instant.now()
        );
        when(participantRepository.findById(roomId, participantId))
            .thenReturn(Optional.of(participant));
        when(connectionRepository.refreshHeartbeat(
            participantId,
            roomId,
            Duration.ofSeconds(15)
        )).thenReturn(HeartbeatRefreshResult.SUCCESS);
        doReturn(disconnectedFuture, reconnectedFuture)
            .when(scheduler)
            .schedule(tasks.capture(), any(Instant.class));
        RoomConnectionService service = new RoomConnectionService(
            connectionRepository,
            participantRepository,
            publisher,
            new RoomConnectionProperties(Duration.ofSeconds(15)),
            scheduler,
            applicationEventPublisher
        );

        // 세션 추적 도입 후 disconnected는 "등록된 마지막 소켓"이 닫힐 때만 동작하므로,
        // 먼저 connect로 소켓을 등록해야 원래 시나리오(끊김→유예 내 재접속)가 재현된다.
        service.connected(roomId, participantId, "socket-1");
        service.disconnected(roomId, participantId, "socket-1");
        service.connected(roomId, participantId, "socket-2");
        tasks.getAllValues().getFirst().run();

        verify(disconnectedFuture).cancel(false);
        verify(participantRepository, never())
            .leaveIfHeartbeatExpired(roomId, participantId);
        verify(applicationEventPublisher, never()).publishEvent(any());
    }
}
