package com.plaiground.domain.game.ninja.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plaiground.domain.game.common.ws.GameEventPublisher;
import com.plaiground.domain.game.ninja.domain.Effect;
import com.plaiground.domain.game.ninja.domain.Gesture;
import com.plaiground.domain.game.ninja.domain.Skill;
import com.plaiground.domain.game.ninja.domain.SkillGesture;
import com.plaiground.domain.game.ninja.domain.SkillGestureId;
import com.plaiground.domain.game.ninja.dto.AttackRequest;
import com.plaiground.domain.game.ninja.dto.AttackResponse;
import com.plaiground.domain.game.ninja.dto.RankingEntry;
import com.plaiground.domain.game.ninja.dto.RoundSkillResponse;
import com.plaiground.domain.game.ninja.dto.TargetRequest;
import com.plaiground.domain.game.ninja.dto.TargetResponse;
import com.plaiground.domain.game.ninja.repository.NinjaRedisRepository;
import com.plaiground.domain.game.ninja.repository.SkillRepository;
import com.plaiground.domain.game.ninja.ws.NinjaEventPublisher;
import com.plaiground.domain.game.ninja.ws.payload.GameEndedPayload;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.domain.RoomStatus;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.global.exception.BusinessException;
import com.plaiground.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

// RoomRepository/Redis/JPA/스케줄러를 전부 모킹해서 인프라 없이 NinjaGameService의 판정 로직만 검증한다 —
// 특히 공격권 경합(HSETNX 성공/실패에 따른 분기)과 HP/탈락/순위 계산이 핵심.
@ExtendWith(MockitoExtension.class)
class NinjaGameServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private NinjaRedisRepository ninjaRedis;
    @Mock
    private NinjaEventPublisher eventPublisher;
    @Mock
    private GameEventPublisher gameEventPublisher;
    @Mock
    private TaskScheduler taskScheduler;

    private NinjaGameService service;

    private final UUID roomId = UUID.randomUUID();
    private final String roomCode = "ABC123";
    private final int seq = 1;
    private final int round = 3;

    private Skill skill;

    @BeforeEach
    void setUp() {
        service = new NinjaGameService(
            roomRepository,
            skillRepository,
            ninjaRedis,
            eventPublisher,
            gameEventPublisher,
            taskScheduler
        );

        Room room = new Room(roomId, roomCode, UUID.randomUUID(), 4, RoomStatus.PLAYING, seq, Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        // 실제 TaskScheduler는 항상 Future를 반환하는데(null 아님), 스텁 안 해두면 Mockito가 기본값
        // null을 반환해서 서비스 쪽 pendingTimeouts.put(key, future)가 NPE 난다 — 계약대로 맞춰줌.
        // lenient(): 라운드를 안 여는 테스트(예외 케이스들)에서는 이 스텁이 안 쓰여도 경고 안 나게.
        // schedule()의 리턴 타입(ScheduledFuture<?>)이 와일드카드 캡처 때문에 when(...).thenReturn(...)
        // 형태로는 타입이 안 맞아서, 제네릭 체크를 우회하는 doReturn(...).when(...) 형태를 쓴다.
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        lenient().doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        Effect effect = Effect.builder()
            .id(2L).name("골드 버스트").color("#ffd700")
            .particleCount(28).life(20).radiusMin(2).radiusMax(5).speedMin(1).speedMax(3)
            .build();
        Gesture horse = Gesture.builder().id(5L).name("Horse").labelKr("말").build();
        Gesture snake = Gesture.builder().id(1L).name("snake").labelKr("뱀").build();
        Skill baseSkill = Skill.builder().id(10L).name("뇌절").skillDesc("설명").damage(20).effect(effect).build();
        SkillGesture step1 = SkillGesture.builder().id(new SkillGestureId(10L, 1)).skill(baseSkill).gesture(horse).build();
        SkillGesture step2 = SkillGesture.builder().id(new SkillGestureId(10L, 2)).skill(baseSkill).gesture(snake).build();
        skill = Skill.builder().id(10L).name("뇌절").skillDesc("설명").damage(20).effect(effect)
            .gestures(List.of(step1, step2))
            .build();
    }

    // ---- getRoundSkill ----

    @Test
    void getRoundSkill_returnsOrderedSequence_whenRoundExists() {
        when(ninjaRedis.roundExists(roomCode, seq, round)).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));

        RoundSkillResponse response = service.getRoundSkill(roomId, round);

        assertThat(response.skillId()).isEqualTo(10L);
        assertThat(response.gestures()).hasSize(2);
        assertThat(response.gestures().get(0).gestureName()).isEqualTo("Horse");
        assertThat(response.gestures().get(1).gestureName()).isEqualTo("snake");
    }

    @Test
    void getRoundSkill_throws_whenRoundMissing() {
        when(ninjaRedis.roundExists(roomCode, seq, round)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getRoundSkill(roomId, round));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ROUND_NOT_FOUND);
    }

    // ---- attack ----

    @Test
    void attack_succeeds_whenFirstToClaim() {
        String token = "player-1";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, token)).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);
        when(ninjaRedis.claimAttacker(roomCode, seq, round, token)).thenReturn(true);
        when(ninjaRedis.isRoundClosed(roomCode, seq, round)).thenReturn(false);

        AttackResponse response = service.attack(roomId, round, token, new AttackRequest(10L));

        assertThat(response.attackerToken()).isEqualTo(token);
        assertThat(response.skillId()).isEqualTo(10L);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:attack-won"), any());
        verify(ninjaRedis).recordAttackSkill(eq(roomCode), eq(seq), eq(round), eq(10L), any(Instant.class));
    }

    @Test
    void attack_throws_whenSomeoneAlreadyClaimed() {
        String token = "player-2";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, token)).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);
        when(ninjaRedis.claimAttacker(roomCode, seq, round, token)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, token, new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ALREADY_CLAIMED);
        verify(eventPublisher, never()).publish(any(), anyString(), any());
    }

    @Test
    void attack_throws_whenSkillDoesNotMatchRoundRequirement() {
        String token = "player-1";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, token)).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, token, new AttackRequest(999L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_WRONG_SKILL);
        verify(ninjaRedis, never()).claimAttacker(any(), anyInt(), anyInt(), any());
    }

    @Test
    void attack_throws_whenParticipantAlreadyEliminated() {
        String token = "dead-player";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, token)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, token, new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_NOT_ALIVE);
    }

    @Test
    void attack_throws_whenRoundIsStale() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round + 1);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, "player-1", new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_STALE_ROUND);
    }

    // ---- target ----

    @Test
    void target_appliesDamageAndAdvancesRound_whenSurvivorsRemain() {
        String attacker = "attacker-token";
        String target = "target-token";

        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.closeRound(roomCode, seq, round, "TARGET")).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));
        when(ninjaRedis.decrementHp(roomCode, seq, target, 20)).thenReturn(80L);
        when(ninjaRedis.getAlivePlayers(roomCode, seq)).thenReturn(Set.of(attacker, target, "third-player"));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(10);
        when(ninjaRedis.getSkillIdForRound(roomCode, seq, round + 1)).thenReturn(20L);

        TargetResponse response = service.target(roomId, round, attacker, new TargetRequest(target));

        assertThat(response.damage()).isEqualTo(20);
        assertThat(response.targetHpAfter()).isEqualTo(80);
        assertThat(response.targetEliminated()).isFalse();
        assertThat(response.gameEnded()).isFalse();

        verify(ninjaRedis, never()).eliminate(any(), anyInt(), any(), any());
        verify(eventPublisher).publish(eq(roomId), eq("ninja:attack-resolved"), any());
        verify(eventPublisher).publish(eq(roomId), eq("ninja:round-started"), any());
        verify(ninjaRedis).openRound(eq(roomCode), eq(seq), eq(round + 1), eq(20L), any(Instant.class));
    }

    @Test
    void target_eliminatesAndEndsGame_whenOnlyOneSurvivorRemains() {
        String attacker = "attacker-token";
        String target = "target-token";

        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.closeRound(roomCode, seq, round, "TARGET")).thenReturn(true);
        when(ninjaRedis.getRoundSkillId(roomCode, seq, round)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));
        when(ninjaRedis.decrementHp(roomCode, seq, target, 20)).thenReturn(-5L);
        when(ninjaRedis.getAlivePlayers(roomCode, seq)).thenReturn(Set.of(attacker));
        when(ninjaRedis.getAllHp(roomCode, seq)).thenReturn(Map.<Object, Object>of(attacker, "60"));
        when(ninjaRedis.getEliminatedOrderDesc(roomCode, seq)).thenReturn(List.of(target, "earlier-victim"));

        TargetResponse response = service.target(roomId, round, attacker, new TargetRequest(target));

        assertThat(response.targetHpAfter()).isEqualTo(0);
        assertThat(response.targetEliminated()).isTrue();
        assertThat(response.gameEnded()).isTrue();

        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(target), any(Instant.class));
        verify(ninjaRedis, never()).openRound(any(), anyInt(), anyInt(), any(), any());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:game-ended"), payloadCaptor.capture());
        GameEndedPayload payload = (GameEndedPayload) payloadCaptor.getValue();

        assertThat(payload.ranking()).extracting(RankingEntry::token)
            .containsExactly(attacker, target, "earlier-victim");
        assertThat(payload.ranking()).extracting(RankingEntry::rank)
            .containsExactly(1, 2, 3);
    }

    @Test
    void target_throws_whenCallerIsNotTheConfirmedAttacker() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn("real-attacker");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, "impostor", new TargetRequest("someone")));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_NOT_ATTACKER);
    }

    @Test
    void target_throws_whenTargetingSelf() {
        String attacker = "attacker-token";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn(attacker);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, attacker, new TargetRequest(attacker)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_INVALID_TARGET);
    }

    @Test
    void target_throws_whenTargetAlreadySet() {
        String attacker = "attacker-token";
        String target = "target-token";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, target)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, attacker, new TargetRequest(target)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_TARGET_ALREADY_SET);
    }

    @Test
    void target_throws_whenRoundAlreadyClosedByTimeoutRace() {
        String attacker = "attacker-token";
        String target = "target-token";
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getAttacker(roomCode, seq, round)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.closeRound(roomCode, seq, round, "TARGET")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, attacker, new TargetRequest(target)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ROUND_CLOSED);

        verify(ninjaRedis, never()).decrementHp(any(), anyInt(), any(), anyInt());
    }

    // ---- startSession ----

    @Test
    void startSession_clearsPreviousSessionThenShufflesSkillsAndStartsFirstRound() {
        Set<String> tokens = Set.of("p1", "p2", "p3");
        when(skillRepository.findAllIds()).thenReturn(List.of(10L, 20L, 30L));
        when(ninjaRedis.getSkillIdForRound(roomCode, seq, 1)).thenReturn(10L);

        service.startSession(roomId, 3L, tokens, 5);

        // 이전 판 잔여 데이터(공격권/HP/순위 등)가 새 게임에 섞여 들어가는 걸 막기 위해
        // 스킬 셔플보다 먼저 세션을 통째로 지워야 한다.
        InOrder inOrder = inOrder(ninjaRedis);
        inOrder.verify(ninjaRedis).clearSession(roomCode, seq);
        inOrder.verify(ninjaRedis).saveSkillOrder(eq(roomCode), eq(seq), any());

        verify(ninjaRedis).setTotalRounds(roomCode, seq, 5);
        verify(ninjaRedis).setGameId(roomCode, seq, 3L);
        verify(ninjaRedis).saveSkillOrder(eq(roomCode), eq(seq),
            argThat(list -> list.size() == 3 && list.containsAll(List.of(10L, 20L, 30L))));
        verify(ninjaRedis).initAlivePlayers(roomCode, seq, tokens);
        verify(ninjaRedis).initPlayerHp(roomCode, seq, tokens, 100);
        verify(gameEventPublisher).publishStarted(roomId, 3L, seq, 5);
        verify(ninjaRedis).openRound(eq(roomCode), eq(seq), eq(1), eq(10L), any(Instant.class));
        verify(eventPublisher).publish(eq(roomId), eq("ninja:round-started"), any());

        InOrder eventOrder = inOrder(gameEventPublisher, eventPublisher);
        eventOrder.verify(gameEventPublisher).publishStarted(roomId, 3L, seq, 5);
        eventOrder.verify(eventPublisher).publish(eq(roomId), eq("ninja:round-started"), any());
    }
}
