package com.plaiground.domain.game.ninja.service;

import com.plaiground.domain.game.ninja.domain.Skill;
import com.plaiground.domain.game.ninja.dto.AttackRequest;
import com.plaiground.domain.game.ninja.dto.AttackResponse;
import com.plaiground.domain.game.ninja.dto.NinjaStateResponse;
import com.plaiground.domain.game.ninja.dto.RankingEntry;
import com.plaiground.domain.game.ninja.dto.RoundSkillResponse;
import com.plaiground.domain.game.ninja.dto.TargetRequest;
import com.plaiground.domain.game.ninja.dto.TargetResponse;
import com.plaiground.domain.game.ninja.repository.NinjaRedisRepository;
import com.plaiground.domain.game.ninja.repository.SkillRepository;
import com.plaiground.domain.game.ninja.ws.NinjaEventPublisher;
import com.plaiground.domain.game.ninja.ws.payload.AttackResolvedPayload;
import com.plaiground.domain.game.ninja.ws.payload.AttackWonPayload;
import com.plaiground.domain.game.ninja.ws.payload.GameEndedPayload;
import com.plaiground.domain.game.ninja.ws.payload.RoundStartedPayload;
import com.plaiground.domain.game.ninja.ws.payload.RoundTimeoutPayload;
import com.plaiground.domain.room.domain.Room;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.global.exception.BusinessException;
import com.plaiground.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 라운드 배정/공격권 경합/HP·탈락/게임 종료까지 닌자 게임 하나의 생명주기를 전부 여기서 오케스트레이션한다.
// room/course/session의 생성·진행(다음 게임으로 넘어가는 것)은 이 도메인 책임이 아니라 다루지 않는다 —
// startSession()은 해당 상위 흐름이 "닌자 세션이 열렸다"고 알려줄 때 호출되는 진입점이라고 가정한다.
@Service
public class NinjaGameService {

    // 요구사항 명세에 구체적 수치가 없어 임의로 잡은 값 — 튜닝 필요.
    private static final int INITIAL_HP = 100;
    private static final Duration ROUND_DURATION = Duration.ofSeconds(15);

    private final RoomRepository roomRepository;
    private final SkillRepository skillRepository;
    private final NinjaRedisRepository ninjaRedis;
    private final NinjaEventPublisher eventPublisher;
    private final TaskScheduler taskScheduler;

    public NinjaGameService(
        RoomRepository roomRepository,
        SkillRepository skillRepository,
        NinjaRedisRepository ninjaRedis,
        NinjaEventPublisher eventPublisher,
        TaskScheduler taskScheduler
    ) {
        this.roomRepository = roomRepository;
        this.skillRepository = skillRepository;
        this.ninjaRedis = ninjaRedis;
        this.eventPublisher = eventPublisher;
        this.taskScheduler = taskScheduler;
    }

    @Transactional
    public void startSession(UUID roomId, Set<String> participantTokens) {
        Room room = resolveRoom(roomId);
        int seq = room.currentSessionSeq();

        List<Long> skillIds = new ArrayList<>(skillRepository.findAllIds());
        Collections.shuffle(skillIds);
        ninjaRedis.saveSkillOrder(room.roomCode(), seq, skillIds);
        ninjaRedis.initAlivePlayers(room.roomCode(), seq, participantTokens);
        ninjaRedis.initPlayerHp(room.roomCode(), seq, participantTokens, INITIAL_HP);

        startRound(room, seq, 1);
    }

    @Transactional(readOnly = true)
    public RoundSkillResponse getRoundSkill(UUID gameId, int round) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        if (!ninjaRedis.roundExists(room.roomCode(), seq, round)) {
            throw new BusinessException(ErrorCode.NINJA_ROUND_NOT_FOUND);
        }
        Skill skill = findRoundSkill(room.roomCode(), seq, round);
        return RoundSkillResponse.of(round, skill);
    }

    @Transactional(readOnly = true)
    public NinjaStateResponse getState(UUID gameId) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        String roomCode = room.roomCode();

        Integer round = ninjaRedis.getCurrentRound(roomCode, seq);
        Integer totalRounds = ninjaRedis.getTotalRounds(roomCode, seq);
        List<String> alive = new ArrayList<>(ninjaRedis.getAlivePlayers(roomCode, seq));
        Map<String, Integer> hp = ninjaRedis.getAllHp(roomCode, seq).entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey().toString(), e -> parseHp(e.getValue())));
        String attacker = round == null ? null : ninjaRedis.getAttacker(roomCode, seq, round);

        return new NinjaStateResponse(
            round == null ? 0 : round,
            totalRounds == null ? 0 : totalRounds,
            alive,
            hp,
            attacker
        );
    }

    // 손동작 완성 판정 제출 = 공격권 선점 시도. 동시 완성은 claimAttacker의 HSETNX가 원자적으로 해소한다.
    // JPA 접근이 없어(전부 Redis) 트랜잭션 경계가 필요 없다.
    public AttackResponse attack(UUID gameId, int round, String participantToken, AttackRequest request) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        String roomCode = room.roomCode();

        requireCurrentRound(roomCode, seq, round);
        if (!ninjaRedis.isAlive(roomCode, seq, participantToken)) {
            throw new BusinessException(ErrorCode.NINJA_NOT_ALIVE);
        }
        Long requiredSkillId = ninjaRedis.getRoundSkillId(roomCode, seq, round);
        if (requiredSkillId == null || !requiredSkillId.equals(request.skillId())) {
            throw new BusinessException(ErrorCode.NINJA_WRONG_SKILL);
        }
        if (!ninjaRedis.claimAttacker(roomCode, seq, round, participantToken)) {
            throw new BusinessException(ErrorCode.NINJA_ALREADY_CLAIMED);
        }
        // 선점엔 성공했지만 그 사이 타임아웃이 먼저 라운드를 닫아버린 극단적인 경합 — 무효 처리.
        if (ninjaRedis.isRoundClosed(roomCode, seq, round)) {
            throw new BusinessException(ErrorCode.NINJA_ROUND_CLOSED);
        }

        ninjaRedis.recordAttackSkill(roomCode, seq, round, requiredSkillId, Instant.now());
        eventPublisher.publish(room.roomId(), "ninja:attack-won",
            new AttackWonPayload(round, participantToken, requiredSkillId));

        return new AttackResponse(round, participantToken, requiredSkillId);
    }

    public TargetResponse target(UUID gameId, int round, String participantToken, TargetRequest request) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        String roomCode = room.roomCode();

        requireCurrentRound(roomCode, seq, round);
        String attacker = ninjaRedis.getAttacker(roomCode, seq, round);
        if (attacker == null || !attacker.equals(participantToken)) {
            throw new BusinessException(ErrorCode.NINJA_NOT_ATTACKER);
        }
        String targetToken = request.targetToken();
        if (targetToken.equals(participantToken) || !ninjaRedis.isAlive(roomCode, seq, targetToken)) {
            throw new BusinessException(ErrorCode.NINJA_INVALID_TARGET);
        }
        if (!ninjaRedis.claimTarget(roomCode, seq, round, targetToken)) {
            throw new BusinessException(ErrorCode.NINJA_TARGET_ALREADY_SET);
        }
        if (!ninjaRedis.closeRound(roomCode, seq, round, "TARGET")) {
            // 타임아웃이 먼저 라운드를 닫아버린 경합 — 데미지는 적용하지 않는다.
            throw new BusinessException(ErrorCode.NINJA_ROUND_CLOSED);
        }

        Skill skill = findRoundSkill(roomCode, seq, round);
        int damage = skill.getDamage();
        long hpAfter = ninjaRedis.decrementHp(roomCode, seq, targetToken, damage);
        boolean eliminated = hpAfter <= 0;
        if (eliminated) {
            ninjaRedis.eliminate(roomCode, seq, targetToken, Instant.now());
        }

        eventPublisher.publish(room.roomId(), "ninja:attack-resolved",
            new AttackResolvedPayload(round, participantToken, targetToken, skill.getId(), damage,
                (int) Math.max(hpAfter, 0), eliminated));

        boolean gameEnded = advanceOrFinish(room, seq, round);

        return new TargetResponse(round, participantToken, targetToken, skill.getId(), damage,
            (int) Math.max(hpAfter, 0), eliminated, gameEnded);
    }

    private void startRound(Room room, int seq, int round) {
        Long skillId = ninjaRedis.getSkillIdForRound(room.roomCode(), seq, round);
        if (skillId == null) {
            // 스킬 풀이 비어있는 등 이상 상태 — 방어적으로 게임을 종료시킨다.
            finishGame(room, seq);
            return;
        }
        Instant startedAt = Instant.now();
        ninjaRedis.openRound(room.roomCode(), seq, round, skillId, startedAt);
        ninjaRedis.setCurrentRound(room.roomCode(), seq, round);

        Instant deadline = startedAt.plus(ROUND_DURATION);
        eventPublisher.publish(room.roomId(), "ninja:round-started", new RoundStartedPayload(round, deadline));
        taskScheduler.schedule(() -> handleTimeout(room, seq, round), deadline);
    }

    private void handleTimeout(Room room, int seq, int round) {
        if (!ninjaRedis.closeRound(room.roomCode(), seq, round, "TIMEOUT")) {
            return; // 이미 대상 지정으로 종료됨 — no-op.
        }
        eventPublisher.publish(room.roomId(), "ninja:round-timeout", new RoundTimeoutPayload(round));
        advanceOrFinish(room, seq, round);
    }

    // 다음 라운드로 진행하거나 게임을 종료한다. 게임이 끝났으면 true.
    private boolean advanceOrFinish(Room room, int seq, int round) {
        Set<String> alive = ninjaRedis.getAlivePlayers(room.roomCode(), seq);
        if (alive.size() <= 1) {
            finishGame(room, seq);
            return true;
        }
        Integer totalRounds = ninjaRedis.getTotalRounds(room.roomCode(), seq);
        if (totalRounds != null && round >= totalRounds) {
            // 안전판: round_count를 다 썼는데도 2명 이상 생존 시 HP 순으로 마무리한다(요구사항에 명시된 케이스는 아님).
            finishGame(room, seq);
            return true;
        }
        startRound(room, seq, round + 1);
        return false;
    }

    private void finishGame(Room room, int seq) {
        List<RankingEntry> ranking = buildRanking(room, seq);
        eventPublisher.publish(room.roomId(), "ninja:game-ended", new GameEndedPayload(ranking));
        // results/totals 반영, 다음 세션으로의 진행은 room/session 공통 흐름(score 도메인)의 책임 —
        // 이 서비스는 최종 순위 산출/전파까지만 담당한다.
    }

    private List<RankingEntry> buildRanking(Room room, int seq) {
        Map<Object, Object> hp = ninjaRedis.getAllHp(room.roomCode(), seq);
        List<String> aliveByHpDesc = new ArrayList<>(ninjaRedis.getAlivePlayers(room.roomCode(), seq));
        aliveByHpDesc.sort(
            Comparator.<String>comparingInt(token -> parseHp(hp.get(token))).reversed()
                .thenComparing(token -> token)
        );
        List<String> eliminatedDesc = ninjaRedis.getEliminatedOrderDesc(room.roomCode(), seq);

        List<RankingEntry> ranking = new ArrayList<>(aliveByHpDesc.size() + eliminatedDesc.size());
        int rank = 1;
        for (String token : aliveByHpDesc) {
            ranking.add(new RankingEntry(token, rank++));
        }
        for (String token : eliminatedDesc) {
            ranking.add(new RankingEntry(token, rank++));
        }
        return ranking;
    }

    private void requireCurrentRound(String roomCode, int seq, int round) {
        Integer currentRound = ninjaRedis.getCurrentRound(roomCode, seq);
        if (currentRound == null || round != currentRound) {
            throw new BusinessException(ErrorCode.NINJA_STALE_ROUND);
        }
    }

    private Skill findRoundSkill(String roomCode, int seq, int round) {
        Long skillId = ninjaRedis.getRoundSkillId(roomCode, seq, round);
        if (skillId == null) {
            throw new BusinessException(ErrorCode.NINJA_ROUND_NOT_FOUND);
        }
        return skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NINJA_ROUND_NOT_FOUND));
    }

    private Room resolveRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private static int parseHp(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}
