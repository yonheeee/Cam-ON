package com.camon.domain.game.ninja.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.ninja.domain.Effect;
import com.camon.domain.game.ninja.domain.Gesture;
import com.camon.domain.game.ninja.domain.Skill;
import com.camon.domain.game.ninja.domain.SkillGesture;
import com.camon.domain.game.ninja.domain.SkillGestureId;
import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.dto.AttackResponse;
import com.camon.domain.game.ninja.dto.RankingEntry;
import com.camon.domain.game.ninja.dto.RoundSkillResponse;
import com.camon.domain.game.ninja.dto.TargetRequest;
import com.camon.domain.game.ninja.dto.TargetResponse;
import com.camon.domain.game.ninja.repository.NinjaRedisRepository;
import com.camon.domain.game.ninja.repository.SkillRepository;
import com.camon.domain.game.ninja.ws.NinjaEventPublisher;
import com.camon.domain.game.ninja.ws.payload.GameEndedPayload;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

// RoomRepository/Redis/JPA/스케줄러를 전부 모킹해 인프라 없이 NinjaGameService의 판정 로직만 검증한다.
// 2단계(판/교환) 구조에서: 교환 내 공격권 경합, 교환→교환/판→판/게임종료 분기, 판별 점수 부여가 핵심.
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
    private GameScoreService gameScoreService;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private NinjaGameService service;

    private final UUID roomId = UUID.randomUUID();
    private final String roomCode = "ABC123";
    private final int seq = 1;
    private final int round = 2;
    private final int exchange = 1;

    // 판 참가자(점수 저장이 UUID 토큰을 요구하므로 UUID 문자열로 둔다).
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();
    private final String attacker = p1.toString();
    private final String target = p2.toString();
    private final String third = p3.toString();

    private Skill skill;

    @BeforeEach
    void setUp() {
        service = new NinjaGameService(
            roomRepository, skillRepository, ninjaRedis, eventPublisher,
            gameEventPublisher, gameScoreService, taskScheduler, applicationEventPublisher
        );

        Room room = new Room(roomId, roomCode, UUID.randomUUID(), 4, RoomStatus.PLAYING, seq, Instant.now());
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        lenient().doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        lenient().when(gameScoreService.saveRoundRanking(any(UUID.class), anyInt(), anyInt(), any()))
            .thenReturn(SaveRoundResult.SUCCESS);
        lenient().when(gameScoreService.getSessionTotals(any(UUID.class), anyInt())).thenReturn(Map.of());

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

    private Runnable captureScheduledTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
        return captor.getValue();
    }

    // ---- getRoundSkill ----

    @Test
    void getRoundSkill_returnsCurrentExchangeSkill() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.exchangeExists(roomCode, seq, round, exchange)).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));

        RoundSkillResponse response = service.getRoundSkill(roomId, round);

        assertThat(response.round()).isEqualTo(round);
        assertThat(response.exchange()).isEqualTo(exchange);
        assertThat(response.skillId()).isEqualTo(10L);
        assertThat(response.gestures()).extracting("gestureName").containsExactly("Horse", "snake");
    }

    @Test
    void getRoundSkill_throws_whenNoActiveExchange() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getRoundSkill(roomId, round));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ROUND_NOT_FOUND);
    }

    // ---- attack ----

    @Test
    void attack_succeeds_whenFirstToClaim() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.isAlive(roomCode, seq, round, attacker)).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);
        when(ninjaRedis.claimAttacker(roomCode, seq, round, exchange, attacker)).thenReturn(true);
        when(ninjaRedis.isExchangeClosed(roomCode, seq, round, exchange)).thenReturn(false);

        AttackResponse response = service.attack(roomId, round, attacker, new AttackRequest(10L));

        assertThat(response.attackerToken()).isEqualTo(attacker);
        assertThat(response.exchange()).isEqualTo(exchange);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:attack-won"), any());
        verify(ninjaRedis).recordAttackSkill(eq(roomCode), eq(seq), eq(round), eq(exchange), eq(10L), any(Instant.class));
    }

    @Test
    void attack_throws_whenSomeoneAlreadyClaimed() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.isAlive(roomCode, seq, round, attacker)).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);
        when(ninjaRedis.claimAttacker(roomCode, seq, round, exchange, attacker)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, attacker, new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ALREADY_CLAIMED);
        verify(eventPublisher, never()).publish(any(), anyString(), any());
    }

    @Test
    void attack_throws_whenSkillDoesNotMatch() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.isAlive(roomCode, seq, round, attacker)).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, attacker, new AttackRequest(999L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_WRONG_SKILL);
        verify(ninjaRedis, never()).claimAttacker(any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void attack_throws_whenParticipantNotAliveInThisBout() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.isAlive(roomCode, seq, round, attacker)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, attacker, new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_NOT_ALIVE);
    }

    @Test
    void attack_throws_whenRoundIsStale() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round + 1);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.attack(roomId, round, attacker, new AttackRequest(10L)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_STALE_ROUND);
    }

    // ---- target: 교환 진행/판 종료/게임 종료 분기 ----

    private void stubTargetCommon() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, exchange, target)).thenReturn(true);
        when(ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TARGET")).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));
    }

    @Test
    void target_advancesToNextExchange_whenMultipleStillAlive() {
        stubTargetCommon();
        when(ninjaRedis.decrementHp(roomCode, seq, round, target, 20)).thenReturn(80L);
        // 탈락 없음 → 판에 3명 그대로 생존 → 같은 판의 다음 교환으로.
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker, target, third));
        when(ninjaRedis.drawNextSkill(roomCode, seq)).thenReturn(10L);

        TargetResponse response = service.target(roomId, round, attacker, new TargetRequest(target));

        assertThat(response.targetHpAfter()).isEqualTo(80);
        assertThat(response.targetEliminated()).isFalse();
        assertThat(response.boutEnded()).isFalse();
        assertThat(response.gameEnded()).isFalse();

        verify(ninjaRedis, never()).eliminate(any(), anyInt(), anyInt(), any(), any());
        // 판이 안 끝났으니 점수 저장은 아직 없다.
        verify(gameScoreService, never()).saveRoundRanking(any(), anyInt(), anyInt(), any());
        verify(ninjaRedis).enterIntermission(eq(roomCode), eq(seq), any(Instant.class), any(Instant.class));

        captureScheduledTask().run();
        // 다음 교환이 같은 판(round)에서 exchange+1로 열린다.
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(round), eq(exchange + 1), eq(10L), any(Instant.class));
        verify(ninjaRedis).setCurrentExchange(roomCode, seq, exchange + 1);
        verify(ninjaRedis, never()).startBout(any(), anyInt(), anyInt(), any(), anyInt());
    }

    @Test
    void target_endsBoutAndStartsNextBout_whenOneSurvivorRemains_notLastRound() {
        stubTargetCommon();
        when(ninjaRedis.decrementHp(roomCode, seq, round, target, 20)).thenReturn(-5L);
        // 대상 탈락 → 판에 1명만 생존 → 판 종료.
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker));
        when(ninjaRedis.getAllHp(roomCode, seq, round)).thenReturn(Map.<Object, Object>of(attacker, "60"));
        when(ninjaRedis.getEliminatedOrderDesc(roomCode, seq, round)).thenReturn(List.of(target, third));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(3); // round=2 < 3 → 마지막 판 아님
        // 다음 판 준비용
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));
        when(ninjaRedis.drawNextSkill(roomCode, seq)).thenReturn(20L);

        TargetResponse response = service.target(roomId, round, attacker, new TargetRequest(target));

        assertThat(response.targetEliminated()).isTrue();
        assertThat(response.boutEnded()).isTrue();
        assertThat(response.gameEnded()).isFalse();

        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(round), eq(target), any(Instant.class));
        // 이 판의 탈락 순서로 점수 저장: [생존자, 마지막탈락, 첫탈락].
        verify(gameScoreService).saveRoundRanking(roomId, seq, round, List.of(p1, p2, p3));

        captureScheduledTask().run();
        // 다음 판(round+1)이 열린다 — 전원 리셋 후 첫 교환.
        verify(ninjaRedis).startBout(eq(roomCode), eq(seq), eq(round + 1), any(), eq(100));
        verify(ninjaRedis).setCurrentRound(roomCode, seq, round + 1);
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(round + 1), eq(1), eq(20L), any(Instant.class));
    }

    @Test
    void target_endsGame_whenLastRoundBoutEnds() {
        int lastRound = 3;
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(lastRound);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAttacker(roomCode, seq, lastRound, exchange)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, lastRound, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, lastRound, exchange, target)).thenReturn(true);
        when(ninjaRedis.closeExchange(roomCode, seq, lastRound, exchange, "TARGET")).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, lastRound, exchange)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));
        when(ninjaRedis.decrementHp(roomCode, seq, lastRound, target, 20)).thenReturn(-5L);
        when(ninjaRedis.getAlivePlayers(roomCode, seq, lastRound)).thenReturn(Set.of(attacker));
        when(ninjaRedis.getAllHp(roomCode, seq, lastRound)).thenReturn(Map.<Object, Object>of(attacker, "60"));
        when(ninjaRedis.getEliminatedOrderDesc(roomCode, seq, lastRound)).thenReturn(List.of(target, third));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(lastRound); // 마지막 판
        // finishGame: 최종 순위는 누적 세션 점수순.
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));
        when(gameScoreService.getSessionTotals(roomId, seq))
            .thenReturn(Map.of(p1, 15L, p2, 9L, p3, 6L));

        TargetResponse response = service.target(roomId, lastRound, attacker, new TargetRequest(target));

        assertThat(response.boutEnded()).isTrue();
        assertThat(response.gameEnded()).isTrue();
        verify(gameScoreService).saveRoundRanking(roomId, seq, lastRound, List.of(p1, p2, p3));
        verify(ninjaRedis, never()).startBout(any(), anyInt(), anyInt(), any(), anyInt());

        captureScheduledTask().run(); // 이펙트 종료 시점의 마무리 태스크
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:game-ended"), payloadCaptor.capture());
        GameEndedPayload payload = (GameEndedPayload) payloadCaptor.getValue();
        // 누적 점수 15/9/6 → p1,p2,p3 순.
        assertThat(payload.ranking()).extracting(RankingEntry::token).containsExactly(attacker, target, third);
        assertThat(payload.ranking()).extracting(RankingEntry::rank).containsExactly(1, 2, 3);
        verify(ninjaRedis).saveRanking(roomCode, seq, List.of(attacker, target, third));
    }

    @Test
    void target_throws_whenCallerIsNotAttacker() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn("real-attacker");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, "impostor", new TargetRequest(target)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_NOT_ATTACKER);
    }

    @Test
    void target_throws_whenTargetingSelf() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(attacker);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, attacker, new TargetRequest(attacker)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_INVALID_TARGET);
    }

    @Test
    void target_throws_whenExchangeAlreadyClosedByTimeoutRace() {
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(attacker);
        when(ninjaRedis.isAlive(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.claimTarget(roomCode, seq, round, exchange, target)).thenReturn(true);
        when(ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TARGET")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.target(roomId, round, attacker, new TargetRequest(target)));
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NINJA_ROUND_CLOSED);
        verify(ninjaRedis, never()).decrementHp(any(), anyInt(), anyInt(), any(), anyInt());
    }

    // ---- startSession ----

    @Test
    void startSession_clearsThenShufflesAndStartsFirstBout() {
        Set<String> tokens = Set.of(attacker, target, third);
        when(skillRepository.findAllIds()).thenReturn(List.of(10L, 20L, 30L));
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(tokens);
        when(ninjaRedis.drawNextSkill(roomCode, seq)).thenReturn(10L);

        service.startSession(roomId, 3L, tokens, 5);

        InOrder inOrder = inOrder(ninjaRedis);
        inOrder.verify(ninjaRedis).clearSession(roomCode, seq);
        inOrder.verify(ninjaRedis).saveSkillOrder(eq(roomCode), eq(seq), any());

        verify(ninjaRedis).setTotalRounds(roomCode, seq, 5);
        verify(ninjaRedis).setGameId(roomCode, seq, 3L);
        verify(ninjaRedis).saveParticipants(roomCode, seq, tokens);
        // 첫 판 시작: 전원 풀피 리셋 + 첫 교환 오픈.
        verify(ninjaRedis).startBout(eq(roomCode), eq(seq), eq(1), any(), eq(100));
        verify(ninjaRedis).setCurrentRound(roomCode, seq, 1);
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(1), eq(1), eq(10L), any(Instant.class));
        verify(gameEventPublisher).publishStarted(roomId, 3L, seq, 5);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:round-started"), any());
    }
}
