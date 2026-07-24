package com.camon.domain.game.ninja.service;

import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.ninja.domain.Skill;
import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.dto.AttackResponse;
import com.camon.domain.game.ninja.dto.NextSkillPreview;
import com.camon.domain.game.ninja.dto.NinjaStateResponse;
import com.camon.domain.game.ninja.dto.RankingEntry;
import com.camon.domain.game.ninja.dto.RoundSkillResponse;
import com.camon.domain.game.ninja.dto.TargetRequest;
import com.camon.domain.game.ninja.dto.TargetResponse;
import com.camon.domain.game.ninja.repository.NinjaRedisRepository;
import com.camon.domain.game.ninja.repository.SkillRepository;
import com.camon.domain.game.ninja.ws.NinjaEventPublisher;
import com.camon.domain.game.ninja.ws.payload.AttackResolvedPayload;
import com.camon.domain.game.ninja.ws.payload.AttackWonPayload;
import com.camon.domain.game.ninja.ws.payload.GameEndedPayload;
import com.camon.domain.game.ninja.ws.payload.RoundStartedPayload;
import com.camon.domain.game.ninja.ws.payload.RoundTimeoutPayload;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 라운드 배정/공격권 경합/HP·탈락/게임 종료까지 닌자 게임 하나의 생명주기를 전부 여기서 오케스트레이션한다.
// room/course/session의 생성·진행(다음 게임으로 넘어가는 것)은 이 도메인 책임이 아니라 다루지 않는다 —
// startSession()은 해당 상위 흐름이 "닌자 세션이 열렸다"고 알려줄 때 호출되는 진입점이라고 가정한다.
@Slf4j
@Service
public class NinjaGameService {

    // 요구사항 명세에 구체적 수치가 없어 임의로 잡은 값 — 튜닝 필요.
    private static final int INITIAL_HP = 100;
    private static final Duration ROUND_DURATION = Duration.ofSeconds(30);

    private final RoomRepository roomRepository;
    private final SkillRepository skillRepository;
    private final NinjaRedisRepository ninjaRedis;
    private final NinjaEventPublisher eventPublisher;
    private final GameEventPublisher gameEventPublisher;
    private final GameScoreService gameScoreService;
    private final TaskScheduler taskScheduler;

    // 라운드 타임아웃은 JVM 메모리에 떠있는 TaskScheduler 타이머로 도는데, "게임 시작"을 다시
    // 누르면(중복 클릭, 여러 명이 거의 동시에 클릭 등) clearSession으로 Redis는 리셋돼도 이전에
    // 예약된 타이머는 취소되지 않고 그대로 살아있었다 — 새 게임도 라운드 번호가 항상 1부터 다시
    // 시작하다 보니, 안 죽은 옛날 타이머가 뒤늦게 발동해서 방금 연 새 라운드를 조기 종료시키는
    // 버그가 있었다(roomCode:seq 좌표가 같으면 충돌). 방(roomCode:seq)마다 "지금 유효한" 타이머
    // 하나만 추적해서, 새 세션/라운드를 열 때마다 이전 걸 확실히 취소한다.
    private final Map<String, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();

    public NinjaGameService(
        RoomRepository roomRepository,
        SkillRepository skillRepository,
        NinjaRedisRepository ninjaRedis,
        NinjaEventPublisher eventPublisher,
        GameEventPublisher gameEventPublisher,
        GameScoreService gameScoreService,
        TaskScheduler taskScheduler
    ) {
        this.roomRepository = roomRepository;
        this.skillRepository = skillRepository;
        this.ninjaRedis = ninjaRedis;
        this.eventPublisher = eventPublisher;
        this.gameEventPublisher = gameEventPublisher;
        this.gameScoreService = gameScoreService;
        this.taskScheduler = taskScheduler;
    }

    // 요구사항명세서 GAME02_RANGE01: 닌자는 2~4명. 1명으로 시작하면 alive_players가 처음부터
    // 1명이라 라운드가 끝나자마자 "생존자 1명 — 게임 종료" 조건에 걸려서 바로 그 사람이 자동 1위가
    // 되는 무의미한 상황이 생긴다 — 프론트에서도 막아뒀지만(상대 감지 전 시작 버튼 비활성화)
    // 서버가 최종적으로 검증해야 진짜 안전하다.
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4;

    @Transactional
    public void startSession(UUID roomId, Long gameId, Set<String> participantTokens, int totalRounds) {
        if (participantTokens.size() < MIN_PLAYERS) {
            throw new BusinessException(ErrorCode.NINJA_NOT_ENOUGH_PLAYERS);
        }
        if (participantTokens.size() > MAX_PLAYERS) {
            throw new BusinessException(ErrorCode.NINJA_TOO_MANY_PLAYERS);
        }

        Room room = resolveRoom(roomId);
        int seq = room.currentSessionSeq();
        log.info("[Service] startSession : roomCode={} seq={} participants={} totalRounds={}",
            room.roomCode(), seq, participantTokens, totalRounds);

        // 같은 방(테스트 고정 roomCode)에 이전 세션 잔여 데이터가 남아있으면 새 게임 시작 시 그대로
        // 섞여 들어간다(HP/공격권/순위가 이전 판 값으로 오염됨) — 매번 완전히 새로 시작하도록 먼저 지운다.
        ninjaRedis.clearSession(room.roomCode(), seq);
        // Redis 리셋과 별개로, 이전 세션에서 예약해둔 라운드 타임아웃 타이머가 아직 안 죽었을 수
        // 있어서(예: "시작" 중복 클릭) 명시적으로 취소한다 — 안 그러면 그 타이머가 뒤늦게 발동해서
        // 방금 연 새 라운드를 조기 종료시킨다.
        cancelPendingTimeout(room.roomCode(), seq);

        ninjaRedis.setTotalRounds(room.roomCode(), seq, totalRounds);
        ninjaRedis.setGameId(room.roomCode(), seq, gameId);
        List<Long> skillIds = new ArrayList<>(skillRepository.findAllIds());
        Collections.shuffle(skillIds);
        ninjaRedis.saveSkillOrder(room.roomCode(), seq, skillIds);
        ninjaRedis.initAlivePlayers(room.roomCode(), seq, participantTokens);
        ninjaRedis.initPlayerHp(room.roomCode(), seq, participantTokens, INITIAL_HP);
        log.info("[Service] startSession : 스킬 {}개 셔플 완료, 초기 HP={}로 세팅", skillIds.size(), INITIAL_HP);

        gameEventPublisher.publishStarted(
            room.roomId(),
            gameId,
            seq,
            totalRounds
        );
        startRound(room, seq, 1);
    }

    // startSession()과 달리 라운드를 새로 열지 않고 그냥 "게임이 시작 안 된 상태"로만 되돌린다 —
    // 프론트의 "게임 초기화" 버튼용. 인원 검증도 안 한다(누구든 눌러서 판을 리셋할 수 있어야 함).
    public void resetSession(UUID roomId) {
        Room room = resolveRoom(roomId);
        int seq = room.currentSessionSeq();
        ninjaRedis.clearSession(room.roomCode(), seq);
        cancelPendingTimeout(room.roomCode(), seq);
        log.info("[Service] resetSession : roomCode={} seq={} 초기화 완료", room.roomCode(), seq);
    }

    @Transactional(readOnly = true)
    public RoundSkillResponse getRoundSkill(UUID gameId, int round) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        if (!ninjaRedis.roundExists(room.roomCode(), seq, round)) {
            throw new BusinessException(ErrorCode.NINJA_ROUND_NOT_FOUND);
        }
        Skill skill = findRoundSkill(room.roomCode(), seq, round);
        return RoundSkillResponse.of(round, skill, findNextSkillPreview(room.roomCode(), seq, round));
    }

    // skill_order가 세션 시작 시 이미 셔플/고정돼 있어서 다음 라운드 스킬도 미리 알 수 있다 —
    // 마지막 라운드면(다음 라운드가 없으면) null.
    private NextSkillPreview findNextSkillPreview(String roomCode, int seq, int round) {
        Integer totalRounds = ninjaRedis.getTotalRounds(roomCode, seq);
        if (totalRounds != null && round >= totalRounds) {
            return null;
        }
        Long nextSkillId = ninjaRedis.getSkillIdForRound(roomCode, seq, round + 1);
        if (nextSkillId == null) {
            return null;
        }
        return skillRepository.findById(nextSkillId).map(NextSkillPreview::of).orElse(null);
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
        List<String> rankingTokens = ninjaRedis.getRanking(roomCode, seq);
        List<RankingEntry> ranking = IntStream.range(0, rankingTokens.size())
            .mapToObj(i -> new RankingEntry(rankingTokens.get(i), i + 1))
            .toList();

        return new NinjaStateResponse(
            round == null ? 0 : round,
            totalRounds == null ? 0 : totalRounds,
            alive,
            hp,
            attacker,
            ranking
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
            log.info("[Service] attack : round={} participantToken={} 공격권 선점 실패(이미 다른 참가자가 선점)",
                round, participantToken);
            throw new BusinessException(ErrorCode.NINJA_ALREADY_CLAIMED);
        }
        // 선점엔 성공했지만 그 사이 타임아웃이 먼저 라운드를 닫아버린 극단적인 경합 — 무효 처리.
        if (ninjaRedis.isRoundClosed(roomCode, seq, round)) {
            log.info("[Service] attack : round={} participantToken={} 선점 성공했으나 라운드가 이미 닫힘(타임아웃 경합)",
                round, participantToken);
            throw new BusinessException(ErrorCode.NINJA_ROUND_CLOSED);
        }

        ninjaRedis.recordAttackSkill(roomCode, seq, round, requiredSkillId, Instant.now());
        eventPublisher.publish(room.roomId(), "ninja:attack-won",
            new AttackWonPayload(round, participantToken, requiredSkillId));
        log.info("[Service] attack : round={} participantToken={} 공격권 선점 성공", round, participantToken);

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
        log.info("[Service] target : round={} attacker={} target={} skill={}({}) damage={} hpAfter={} eliminated={}",
            round, participantToken, targetToken, skill.getName(), skill.getId(), damage, Math.max(hpAfter, 0), eliminated);

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
        log.info("[Service] startRound : round={} 시작 (roomCode={} seq={} skillId={} 제한시간={}초)",
            round, room.roomCode(), seq, skillId, ROUND_DURATION.toSeconds());
        eventPublisher.publish(room.roomId(), "ninja:round-started", new RoundStartedPayload(round, deadline));
        ScheduledFuture<?> future = taskScheduler.schedule(() -> handleTimeout(room, seq, round), deadline);
        pendingTimeouts.put(timerKey(room.roomCode(), seq), future);
    }

    private void handleTimeout(Room room, int seq, int round) {
        pendingTimeouts.remove(timerKey(room.roomCode(), seq));
        if (!ninjaRedis.closeRound(room.roomCode(), seq, round, "TIMEOUT")) {
            return; // 이미 대상 지정으로 종료됐거나, 이 타이머 자체가 이전 세션의 좀비였던 경우 — no-op.
        }
        log.info("[Service] handleTimeout : round={} 제한시간 내에 아무도 콤보를 완성 못함 — 공격 없이 종료", round);
        eventPublisher.publish(room.roomId(), "ninja:round-timeout", new RoundTimeoutPayload(round));
        advanceOrFinish(room, seq, round);
    }

    // 다음 라운드로 진행하거나 게임을 종료한다. 게임이 끝났으면 true.
    private boolean advanceOrFinish(Room room, int seq, int round) {
        saveRoundScore(room, seq, round);
        Set<String> alive = ninjaRedis.getAlivePlayers(room.roomCode(), seq);
        if (alive.size() <= 1) {
            log.info("[Service] advanceOrFinish : round={} 생존자 {}명 — 게임 종료 조건", round, alive.size());
            finishGame(room, seq);
            return true;
        }
        Integer totalRounds = ninjaRedis.getTotalRounds(room.roomCode(), seq);
        if (totalRounds != null && round >= totalRounds) {
            // 안전판: round_count를 다 썼는데도 2명 이상 생존 시 HP 순으로 마무리한다(요구사항에 명시된 케이스는 아님).
            log.info("[Service] advanceOrFinish : round={} totalRounds={} 도달 — 게임 종료(HP 순 마무리)", round, totalRounds);
            finishGame(room, seq);
            return true;
        }
        log.info("[Service] advanceOrFinish : round={} → round={}로 진행 (생존자 {}명)", round, round + 1, alive.size());
        startRound(room, seq, round + 1);
        return false;
    }

    private void saveRoundScore(Room room, int seq, int round) {
        List<UUID> participantIdsByRank = buildRanking(room, seq).stream()
            .map(entry -> UUID.fromString(entry.token()))
            .toList();
        SaveRoundResult result = gameScoreService.saveRoundRanking(
            room.roomId(),
            seq,
            round,
            participantIdsByRank
        );
        if (result != SaveRoundResult.SUCCESS
            && result != SaveRoundResult.ALREADY_SAVED) {
            throw new IllegalStateException(
                "Failed to save ninja round score: " + result
            );
        }
    }

    private void finishGame(Room room, int seq) {
        List<RankingEntry> ranking = buildRanking(room, seq);
        ninjaRedis.saveRanking(room.roomCode(), seq, ranking.stream().map(RankingEntry::token).toList());
        log.info("[Service] finishGame : roomCode={} seq={} 최종 순위={}", room.roomCode(), seq, ranking);
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

    private String timerKey(String roomCode, int seq) {
        return roomCode + ":" + seq;
    }

    private void cancelPendingTimeout(String roomCode, int seq) {
        ScheduledFuture<?> future = pendingTimeouts.remove(timerKey(roomCode, seq));
        if (future != null) {
            future.cancel(false);
        }
    }

    private Room resolveRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private static int parseHp(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}
