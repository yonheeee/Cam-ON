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
import com.camon.domain.game.ninja.domain.NinjaPhase;
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
import com.camon.domain.game.ninja.ws.payload.AttackWonPayload;
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

        // handleTimeout/handleTargetTimeout 테스트는 Room을 직접 넘겨 findById를 안 타므로 lenient.
        Room room = new Room(roomId, roomCode, UUID.randomUUID(), 4, RoomStatus.PLAYING, seq, Instant.now());
        lenient().when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        lenient().doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        lenient().when(gameScoreService.saveRoundScores(any(UUID.class), anyInt(), anyInt(), any()))
            .thenReturn(SaveRoundResult.SUCCESS);
        lenient().when(gameScoreService.saveCourseRanking(any(UUID.class), anyInt(), any()))
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
        verify(ninjaRedis).recordAttackSkill(eq(roomCode), eq(seq), eq(round), eq(exchange), eq(10L), any(Instant.class));

        // 공격권 획득 = 교환 30초 타이머를 대상 지정 창(15초)으로 교체. 이게 없으면 교환 데드라인이
        // 살아남아 마감 직전 공격권이 무효 처리되는 버그(NINJA_ROUND_CLOSED)가 재발한다.
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:attack-won"), payloadCaptor.capture());
        AttackWonPayload payload = (AttackWonPayload) payloadCaptor.getValue();
        assertThat(payload.targetDeadlineAt()).isNotNull(); // 프론트 대상 지정 카운트다운의 기준 시각
    }

    // ---- 대상 지정 제한시간 (자동 공격) ----

    @Test
    void handleTargetTimeout_autoAttacksRandomSurvivor_whenNoTargetChosen() {
        // 생존자 = 공격자 + target 하나뿐 → 랜덤이어도 target이 뽑힌다(결정적 검증).
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker, target));
        when(ninjaRedis.claimTarget(roomCode, seq, round, exchange, target)).thenReturn(true);
        when(ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TARGET")).thenReturn(true);
        when(ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange)).thenReturn(10L);
        when(skillRepository.findById(10L)).thenReturn(Optional.of(skill));
        when(ninjaRedis.decrementHp(roomCode, seq, round, target, 20)).thenReturn(80L);

        service.handleTargetTimeout(room(), seq, round, exchange, attacker);

        // 공격이 무산되지 않고 정상 판정 경로(attack-resolved)로 흘러야 한다.
        verify(ninjaRedis).decrementHp(roomCode, seq, round, target, 20);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:attack-resolved"), any());
    }

    @Test
    void handleTargetTimeout_yieldsToManualTarget_whenClaimLost() {
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker, target));
        // 간발의 차로 수동 지정이 먼저 — 자동 지정은 조용히 물러난다.
        when(ninjaRedis.claimTarget(roomCode, seq, round, exchange, target)).thenReturn(false);

        service.handleTargetTimeout(room(), seq, round, exchange, attacker);

        verify(ninjaRedis, never()).decrementHp(any(), anyInt(), anyInt(), anyString(), anyInt());
        verify(eventPublisher, never()).publish(any(), eq("ninja:attack-resolved"), any());
    }

    // ---- 타임아웃 HP 감쇠 ----

    @Test
    void handleTimeout_decaysAllSurvivors_andSplitsPointsEvenlyOnSimultaneousElimination() {
        when(ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TIMEOUT")).thenReturn(true);
        // 감쇠 전 생존 3명 → 감쇠 후 attacker만 생존(b,c는 동시 0 이하)
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round))
            .thenReturn(Set.of(attacker, target, third))  // 감쇠 대상 순회
            .thenReturn(Set.of(attacker))                 // 점수용 동점 그룹
            .thenReturn(Set.of(attacker));                // 스냅샷
        when(ninjaRedis.decrementHp(roomCode, seq, round, attacker, 20)).thenReturn(40L);
        when(ninjaRedis.decrementHp(roomCode, seq, round, target, 20)).thenReturn(0L);
        when(ninjaRedis.decrementHp(roomCode, seq, round, third, 20)).thenReturn(-5L);
        when(ninjaRedis.getAllHp(roomCode, seq, round))
            .thenReturn(Map.<Object, Object>of(attacker, "40", target, "0", third, "-5"));
        // 동시 탈락 = 같은 시각 → 한 그룹
        when(ninjaRedis.getEliminatedWithTimeDesc(roomCode, seq, round))
            .thenReturn(List.of(Map.entry(target, 5000L), Map.entry(third, 5000L)));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(3); // round=2 < 3 → 게임은 계속
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));

        service.handleTimeout(room(), seq, round, exchange);

        // 생존자 전원 감쇠 + 0 이하 일괄 탈락 (같은 시각 = 동점)
        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(round), eq(target), any(Instant.class));
        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(round), eq(third), any(Instant.class));
        // 판 종료(생존 1명): 생존자 5점, 동시 탈락 2명은 (4+3)/2 = 3점 균등 — 토큰 순서로
        // 임의의 4점/3점이 갈리지 않는다.
        verify(gameScoreService).saveRoundScores(roomId, seq, round, Map.of(p1, 5L, p2, 3L, p3, 3L));
    }

    // ---- 진행 중 이탈 (창을 그냥 닫아 하트비트가 만료된 경우 포함) ----

    @Test
    void handleParticipantLeft_keepsRoundGoing_whenTwoSurvivorsRemain() {
        when(ninjaRedis.getPhase(roomCode, seq)).thenReturn(NinjaPhase.ROUND);
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, round, third)).thenReturn(true);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker, target));
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(attacker);

        service.handleParticipantLeft(roomId, p3);

        // 다음 판에 유령이 부활하지 않도록 세션 참가자 집합에서도 빠져야 한다.
        verify(ninjaRedis).removeParticipant(roomCode, seq, third);
        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(round), eq(third), any(Instant.class));
        // 아직 둘이 남았으니 진행 중인 교환은 그대로 둔다.
        verify(ninjaRedis, never()).closeExchange(anyString(), anyInt(), anyInt(), anyInt(), anyString());
        verify(eventPublisher, never()).publish(any(), anyString(), any());
    }

    @Test
    void handleParticipantLeft_endsRound_whenOnlyOneSurvivorRemains() {
        when(ninjaRedis.getPhase(roomCode, seq)).thenReturn(NinjaPhase.ROUND);
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, round, target)).thenReturn(true);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        // 이탈 반영 후 생존자는 attacker 한 명뿐 — 원래는 여기서 판이 끝나야 하는데,
        // 떠난 사람이 생존자로 남아 조건이 영영 안 걸리던 것이 이 버그였다.
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker));
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(null);
        when(ninjaRedis.getAllHp(roomCode, seq, round))
            .thenReturn(Map.<Object, Object>of(attacker, "60", target, "20"));
        when(ninjaRedis.getEliminatedWithTimeDesc(roomCode, seq, round))
            .thenReturn(List.of(Map.entry(target, 5000L)));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(3); // round=2 < 3 → 게임은 계속
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker));

        service.handleParticipantLeft(roomId, p2);

        verify(ninjaRedis).closeExchange(roomCode, seq, round, exchange, "PARTICIPANT_LEFT");
        verify(gameScoreService).saveRoundScores(eq(roomId), eq(seq), eq(round), any());
        verify(eventPublisher).publish(eq(roomId), eq("ninja:round-timeout"), any());
    }

    @Test
    void handleParticipantLeft_closesExchange_whenAttackerLeavesBeforeChoosingTarget() {
        when(ninjaRedis.getPhase(roomCode, seq)).thenReturn(NinjaPhase.ROUND);
        when(ninjaRedis.getCurrentRound(roomCode, seq)).thenReturn(round);
        when(ninjaRedis.isAlive(roomCode, seq, round, attacker)).thenReturn(true);
        when(ninjaRedis.getCurrentExchange(roomCode, seq)).thenReturn(exchange);
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(target, third));
        // 공격권을 쥔 채 나갔다 — 그냥 두면 대상 지정 창(15초)이 통째로 비고, 그 뒤 떠난
        // 사람의 공격으로 남은 사람이 맞는다.
        when(ninjaRedis.getAttacker(roomCode, seq, round, exchange)).thenReturn(attacker);
        when(ninjaRedis.isExchangeClosed(roomCode, seq, round, exchange)).thenReturn(false);
        when(ninjaRedis.getAllHp(roomCode, seq, round))
            .thenReturn(Map.<Object, Object>of(target, "80", third, "80"));

        service.handleParticipantLeft(roomId, p1);

        verify(ninjaRedis).closeExchange(roomCode, seq, round, exchange, "PARTICIPANT_LEFT");
        // 둘이 남았으니 판은 계속 — 점수 저장 없이 다음 교환으로 넘어간다.
        verify(ninjaRedis).enterIntermission(eq(roomCode), eq(seq), eq(null), any(Instant.class));
        verify(gameScoreService, never()).saveRoundScores(any(UUID.class), anyInt(), anyInt(), any());
    }

    @Test
    void handleParticipantLeft_doesNothing_whenNinjaSessionIsNotOpen() {
        // 다른 게임이 진행 중이거나 이미 끝난 세션 — GameParticipantLeaveHandler 계약대로 no-op.
        when(ninjaRedis.getPhase(roomCode, seq)).thenReturn(null);

        service.handleParticipantLeft(roomId, p1);

        verify(ninjaRedis, never()).removeParticipant(anyString(), anyInt(), anyString());
        verify(ninjaRedis, never()).eliminate(anyString(), anyInt(), anyInt(), anyString(), any(Instant.class));
    }

    private Room room() {
        return new Room(roomId, roomCode, UUID.randomUUID(), 4, RoomStatus.PLAYING, seq, Instant.now());
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
    void attack_throws_whenParticipantNotAliveInThisRound() {
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
        assertThat(response.roundEnded()).isFalse();
        assertThat(response.gameEnded()).isFalse();

        verify(ninjaRedis, never()).eliminate(any(), anyInt(), anyInt(), any(), any());
        // 판이 안 끝났으니 점수 저장은 아직 없다.
        verify(gameScoreService, never()).saveRoundScores(any(), anyInt(), anyInt(), any());
        verify(ninjaRedis).enterIntermission(eq(roomCode), eq(seq), any(Instant.class), any(Instant.class));

        captureScheduledTask().run();
        // 다음 교환이 같은 판(round)에서 exchange+1로 열린다.
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(round), eq(exchange + 1), eq(10L), any(Instant.class));
        verify(ninjaRedis).setCurrentExchange(roomCode, seq, exchange + 1);
        verify(ninjaRedis, never()).startRound(any(), anyInt(), anyInt(), any(), anyInt());
    }

    @Test
    void target_endsRoundAndStartsNextRound_whenOneSurvivorRemains_notLastRound() {
        stubTargetCommon();
        when(ninjaRedis.decrementHp(roomCode, seq, round, target, 20)).thenReturn(-5L);
        // 대상 탈락 → 판에 1명만 생존 → 판 종료.
        when(ninjaRedis.getAlivePlayers(roomCode, seq, round)).thenReturn(Set.of(attacker));
        when(ninjaRedis.getAllHp(roomCode, seq, round)).thenReturn(Map.<Object, Object>of(attacker, "60"));
        // 늦게 탈락한 순서 + 탈락 시각(서로 다름 = 동점 아님): target(나중), third(먼저)
        when(ninjaRedis.getEliminatedWithTimeDesc(roomCode, seq, round))
            .thenReturn(List.of(Map.entry(target, 2000L), Map.entry(third, 1000L)));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(3); // round=2 < 3 → 마지막 판 아님
        // 다음 판 준비용
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));
        when(ninjaRedis.drawNextSkill(roomCode, seq)).thenReturn(20L);

        TargetResponse response = service.target(roomId, round, attacker, new TargetRequest(target));

        assertThat(response.targetEliminated()).isTrue();
        assertThat(response.roundEnded()).isTrue();
        assertThat(response.gameEnded()).isFalse();

        verify(ninjaRedis).eliminate(eq(roomCode), eq(seq), eq(round), eq(target), any(Instant.class));
        // 이 판의 탈락 순서로 점수 저장: 생존자 5점, 마지막 탈락 4점, 첫 탈락 3점 (동점 없음).
        verify(gameScoreService).saveRoundScores(roomId, seq, round, Map.of(p1, 5L, p2, 4L, p3, 3L));

        captureScheduledTask().run();
        // 다음 판(round+1)이 열린다 — 전원 리셋 후 첫 교환.
        verify(ninjaRedis).startRound(eq(roomCode), eq(seq), eq(round + 1), any(), eq(100));
        verify(ninjaRedis).setCurrentRound(roomCode, seq, round + 1);
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(round + 1), eq(1), eq(20L), any(Instant.class));
    }

    @Test
    void target_endsGame_whenLastRoundEnds() {
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
        when(ninjaRedis.getEliminatedWithTimeDesc(roomCode, seq, lastRound))
            .thenReturn(List.of(Map.entry(target, 2000L), Map.entry(third, 1000L)));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(lastRound); // 마지막 판
        // finishGame: 최종 순위는 누적 세션 점수순.
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));
        when(gameScoreService.getSessionTotals(roomId, seq))
            .thenReturn(Map.of(p1, 15L, p2, 9L, p3, 6L));

        TargetResponse response = service.target(roomId, lastRound, attacker, new TargetRequest(target));

        assertThat(response.roundEnded()).isTrue();
        assertThat(response.gameEnded()).isTrue();
        verify(gameScoreService).saveRoundScores(roomId, seq, lastRound, Map.of(p1, 5L, p2, 4L, p3, 3L));
        verify(ninjaRedis, never()).startRound(any(), anyInt(), anyInt(), any(), anyInt());

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
    void target_assignsSameFinalRank_whenSessionTotalsAreTied() {
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
        when(ninjaRedis.getEliminatedWithTimeDesc(roomCode, seq, lastRound))
            .thenReturn(List.of(Map.entry(target, 2000L), Map.entry(third, 1000L)));
        when(ninjaRedis.getTotalRounds(roomCode, seq)).thenReturn(lastRound);
        when(ninjaRedis.getParticipants(roomCode, seq)).thenReturn(Set.of(attacker, target, third));
        when(gameScoreService.getSessionTotals(roomId, seq))
            .thenReturn(Map.of(p1, 10L, p2, 10L, p3, 5L));

        TargetResponse response = service.target(roomId, lastRound, attacker, new TargetRequest(target));

        assertThat(response.gameEnded()).isTrue();
        captureScheduledTask().run();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:game-ended"), payloadCaptor.capture());
        GameEndedPayload payload = (GameEndedPayload) payloadCaptor.getValue();
        assertThat(payload.ranking()).extracting(RankingEntry::rank).containsExactly(1, 1, 3);
        verify(gameScoreService).saveCourseRanking(
            eq(roomId),
            eq(seq),
            eq(Map.of(p1, 1, p2, 1, p3, 3))
        );
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
    void startSession_clearsThenShufflesAndStartsFirstRound() {
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
        verify(ninjaRedis).startRound(eq(roomCode), eq(seq), eq(1), any(), eq(100));
        verify(ninjaRedis).setCurrentRound(roomCode, seq, 1);
        verify(ninjaRedis).openExchange(eq(roomCode), eq(seq), eq(1), eq(1), eq(10L), any(Instant.class));
        verify(gameEventPublisher).publishStarted(roomId, 3L, seq, 5);
        verify(eventPublisher).publish(eq(roomId), eq("ninja:round-started"), any());
    }
}
