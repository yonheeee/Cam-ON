package com.camon.domain.game.ninja.service;

import com.camon.domain.game.common.event.GameSessionFinishedEvent;
import com.camon.domain.game.common.repository.SaveRoundResult;
import com.camon.domain.game.common.service.GameScoreService;
import com.camon.domain.game.common.ws.GameEventPublisher;
import com.camon.domain.game.ninja.domain.NinjaPhase;
import com.camon.domain.game.ninja.domain.Skill;
import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.dto.AttackResponse;
import com.camon.domain.game.ninja.dto.RoundResultEntry;
import com.camon.domain.game.ninja.dto.LastAttackResponse;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 닌자 게임 하나의 생명주기를 전부 여기서 오케스트레이션한다.
//
// 2단계 구조:
//   round(판)     = 전원 풀피로 시작해 "최후 1인"이 남을 때까지 벌이는 한 판. 판이 끝나면 그 판의
//                   탈락 순서(생존자 1위, 늦게 탈락할수록 상위)로 5/4/3/2점을 부여한다. 코스가 정한
//                   totalRounds 판만큼 반복하고, 판별 점수를 누적해 최종 순위를 낸다.
//     exchange(교환) = 판 안에서 "스킬콤보→공격권 선점→대상 지정→데미지" 1회. 최후 1인이 남을 때까지 반복.
//
// room/course/session의 생성·진행(다음 게임으로 넘어가는 것)은 이 도메인 책임이 아니다 —
// startSession()은 상위 흐름이 "닌자 세션이 열렸다"고 알려줄 때 호출되는 진입점이라고 가정한다.
@Slf4j
@Service
public class NinjaGameService {

    // 요구사항 명세에 구체적 수치가 없어 임의로 잡은 값 — 튜닝 필요.
    private static final int INITIAL_HP = 100;
    // 한 교환(콤보 완성 경합)의 제한시간.
    private static final Duration EXCHANGE_DURATION = Duration.ofSeconds(30);
    // 공격 resolve 후 다음 교환/판 사이의 인터미션: 이펙트 재생 5초 + 다음 진행 직전 카운트다운 3초.
    // 타임아웃(공격 없음)으로 넘어갈 땐 이펙트가 없어 카운트다운(3초)만 태운다.
    private static final Duration EFFECT_DURATION = Duration.ofSeconds(5);
    private static final Duration COUNTDOWN_DURATION = Duration.ofSeconds(3);
    // 판이 무한히 안 끝나는 것(모두가 계속 타임아웃 등)을 막는 방어적 상한 — 도달하면 현재 HP 순으로 판을 마감한다.
    private static final int MAX_EXCHANGES_PER_ROUND = 50;

    private final RoomRepository roomRepository;
    private final SkillRepository skillRepository;
    private final NinjaRedisRepository ninjaRedis;
    private final NinjaEventPublisher eventPublisher;
    private final GameEventPublisher gameEventPublisher;
    private final GameScoreService gameScoreService;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher applicationEventPublisher;

    // 교환 타임아웃/인터미션 태스크는 JVM 메모리의 TaskScheduler 타이머로 도는데, 리셋/중복 시작 시
    // 옛 타이머가 안 죽고 살아남아 방금 연 교환을 조기 종료시키는 버그가 있었다. 방(roomCode:seq)마다
    // "지금 유효한" 타이머 하나만 추적해서, 새 교환/판/인터미션을 걸 때마다 이전 걸 확실히 취소한다.
    private final Map<String, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();

    public NinjaGameService(
        RoomRepository roomRepository,
        SkillRepository skillRepository,
        NinjaRedisRepository ninjaRedis,
        NinjaEventPublisher eventPublisher,
        GameEventPublisher gameEventPublisher,
        GameScoreService gameScoreService,
        TaskScheduler taskScheduler,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.roomRepository = roomRepository;
        this.skillRepository = skillRepository;
        this.ninjaRedis = ninjaRedis;
        this.eventPublisher = eventPublisher;
        this.gameEventPublisher = gameEventPublisher;
        this.gameScoreService = gameScoreService;
        this.taskScheduler = taskScheduler;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // 요구사항명세서 GAME02_RANGE01: 닌자는 2~4명. 점수 부여(5/4/3/2)도 최대 4명 기준이라 상한을 지킨다.
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
        String roomCode = room.roomCode();
        log.info("[Service] startSession : roomCode={} seq={} participants={} totalRounds={}(판 수)",
            roomCode, seq, participantTokens, totalRounds);

        // 이전 세션 잔여 데이터가 섞여 들어가지 않도록 매번 완전히 새로 시작한다.
        ninjaRedis.clearSession(roomCode, seq);
        cancelPendingTimeout(roomCode, seq);

        ninjaRedis.setTotalRounds(roomCode, seq, totalRounds);
        ninjaRedis.setGameId(roomCode, seq, gameId);
        ninjaRedis.saveParticipants(roomCode, seq, participantTokens);
        List<Long> skillIds = new ArrayList<>(skillRepository.findAllIds());
        Collections.shuffle(skillIds);
        ninjaRedis.saveSkillOrder(roomCode, seq, skillIds);
        log.info("[Service] startSession : 참가자 {}명 저장, 스킬 {}개 셔플 완료", participantTokens.size(), skillIds.size());

        gameEventPublisher.publishStarted(room.roomId(), gameId, seq, totalRounds);
        startRound(room, seq, 1);
    }

    // 라운드를 새로 열지 않고 "게임이 시작 안 된 상태"로만 되돌린다 — 프론트의 "게임 초기화" 버튼용.
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
        String roomCode = room.roomCode();

        Integer currentRound = ninjaRedis.getCurrentRound(roomCode, seq);
        Integer exchange = ninjaRedis.getCurrentExchange(roomCode, seq);
        // 스킬은 "현재 판의 현재 교환" 것을 돌려준다. 프론트가 지난 판 번호로 물어오면(전환 직후 등) 거부.
        if (currentRound == null || exchange == null || round != currentRound
            || !ninjaRedis.exchangeExists(roomCode, seq, currentRound, exchange)) {
            throw new BusinessException(ErrorCode.NINJA_ROUND_NOT_FOUND);
        }
        Skill skill = findExchangeSkill(roomCode, seq, currentRound, exchange);
        return RoundSkillResponse.of(round, exchange, skill, findNextSkillPreview(roomCode, seq));
    }

    // 바로 다음 교환에 나올 스킬 예고(셔플 순서에서 커서가 가리키는 다음 스킬). 없으면 null.
    private NextSkillPreview findNextSkillPreview(String roomCode, int seq) {
        Long nextSkillId = ninjaRedis.peekNextSkill(roomCode, seq);
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
        Integer exchange = ninjaRedis.getCurrentExchange(roomCode, seq);
        Integer totalRounds = ninjaRedis.getTotalRounds(roomCode, seq);

        // alive/hp는 "현재 판" 기준(판마다 리셋). 판이 아직 없으면 빈 값.
        List<String> alive = round == null
            ? List.of()
            : new ArrayList<>(ninjaRedis.getAlivePlayers(roomCode, seq, round));
        Map<String, Integer> hp = round == null
            ? Map.of()
            : ninjaRedis.getAllHp(roomCode, seq, round).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), e -> parseInt(e.getValue())));
        String attacker = (round == null || exchange == null)
            ? null
            : ninjaRedis.getAttacker(roomCode, seq, round, exchange);

        List<String> rankingTokens = ninjaRedis.getRanking(roomCode, seq);
        List<RankingEntry> ranking = IntStream.range(0, rankingTokens.size())
            .mapToObj(i -> new RankingEntry(rankingTokens.get(i), i + 1))
            .toList();

        NinjaPhase phase = ninjaRedis.getPhase(roomCode, seq);
        if (!ranking.isEmpty()) {
            phase = NinjaPhase.ENDED;
        } else if (phase == null && round != null) {
            phase = NinjaPhase.ROUND;
        }
        Instant effectUntil = ninjaRedis.getEffectUntil(roomCode, seq);
        Instant nextRoundAt = ninjaRedis.getNextRoundAt(roomCode, seq);
        LastAttackResponse lastAttack =
            (phase == NinjaPhase.INTERMISSION && effectUntil != null && round != null && exchange != null)
                ? buildLastAttack(roomCode, seq, round, exchange)
                : null;
        // 판 종료 인터미션이면 방금 끝난 판의 순위+획득 점수를 노출한다(공통 GameResult에 이미 저장된 값을 읽음).
        List<RoundResultEntry> roundResult = (phase == NinjaPhase.INTERMISSION && round != null)
            ? buildRoundResult(room, seq, round)
            : null;

        return new NinjaStateResponse(
            round == null ? 0 : round,
            exchange == null ? 0 : exchange,
            totalRounds == null ? 0 : totalRounds,
            alive,
            hp,
            attacker,
            ranking,
            phase,
            effectUntil,
            nextRoundAt,
            lastAttack,
            sessionTotals(room, seq),
            roundResult
        );
    }

    // 방금 끝난 판의 결과 = 그 판의 round results(참가자→획득 점수). 점수 내림차순이 곧 그 판의 순위다.
    // 판이 아직 점수화되지 않았으면(교환 사이/일반 진행) 빈 결과 → null 반환.
    private List<RoundResultEntry> buildRoundResult(Room room, int seq, int round) {
        Map<UUID, Long> results = gameScoreService.getRoundResults(room.roomId(), seq, round);
        if (results.isEmpty()) {
            return null;
        }
        List<Map.Entry<UUID, Long>> sorted = results.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<UUID, Long> e) -> e.getValue()).reversed()
                .thenComparing(e -> e.getKey().toString()))
            .toList();
        return IntStream.range(0, sorted.size())
            .mapToObj(i -> new RoundResultEntry(sorted.get(i).getKey().toString(), i + 1, sorted.get(i).getValue()))
            .toList();
    }

    // 판을 가로질러 누적된 참가자별 점수(최종 발표 합산용). 아직 점수가 없는 참가자도 0으로 채운다.
    private Map<String, Long> sessionTotals(Room room, int seq) {
        Map<UUID, Long> totals = gameScoreService.getSessionTotals(room.roomId(), seq);
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        for (String token : ninjaRedis.getParticipants(room.roomCode(), seq)) {
            result.put(token, totals.getOrDefault(UUID.fromString(token), 0L));
        }
        return result;
    }

    private LastAttackResponse buildLastAttack(String roomCode, int seq, int round, int exchange) {
        Map<Object, Object> attack = ninjaRedis.getAttack(roomCode, seq, round, exchange);
        Object attacker = attack.get("attacker_token");
        Object target = attack.get("target_token");
        if (attacker == null || target == null) {
            return null;
        }
        Object skillId = attack.get("skill_id");
        return new LastAttackResponse(
            attacker.toString(),
            target.toString(),
            skillId == null ? null : Long.valueOf(skillId.toString()),
            parseInt(attack.get("damage")),
            parseInt(attack.get("hp_after")),
            Boolean.parseBoolean(String.valueOf(attack.get("eliminated")))
        );
    }

    // 손동작 완성 판정 제출 = 현재 교환의 공격권 선점 시도. 동시 완성은 claimAttacker의 HSETNX가 원자적으로 해소.
    public AttackResponse attack(UUID gameId, int round, String participantToken, AttackRequest request) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        String roomCode = room.roomCode();

        requireCurrentRound(roomCode, seq, round);
        int exchange = requireCurrentExchange(roomCode, seq);
        if (!ninjaRedis.isAlive(roomCode, seq, round, participantToken)) {
            throw new BusinessException(ErrorCode.NINJA_NOT_ALIVE);
        }
        Long requiredSkillId = ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange);
        if (requiredSkillId == null || !requiredSkillId.equals(request.skillId())) {
            throw new BusinessException(ErrorCode.NINJA_WRONG_SKILL);
        }
        if (!ninjaRedis.claimAttacker(roomCode, seq, round, exchange, participantToken)) {
            log.info("[Service] attack : round={} ex={} token={} 공격권 선점 실패", round, exchange, participantToken);
            throw new BusinessException(ErrorCode.NINJA_ALREADY_CLAIMED);
        }
        // 선점엔 성공했지만 그 사이 타임아웃이 먼저 교환을 닫아버린 극단적인 경합 — 무효 처리.
        if (ninjaRedis.isExchangeClosed(roomCode, seq, round, exchange)) {
            log.info("[Service] attack : round={} ex={} token={} 선점 성공했으나 교환이 이미 닫힘", round, exchange, participantToken);
            throw new BusinessException(ErrorCode.NINJA_ROUND_CLOSED);
        }

        ninjaRedis.recordAttackSkill(roomCode, seq, round, exchange, requiredSkillId, Instant.now());
        eventPublisher.publish(room.roomId(), "ninja:attack-won",
            new AttackWonPayload(round, exchange, participantToken, requiredSkillId));
        log.info("[Service] attack : round={} ex={} token={} 공격권 선점 성공", round, exchange, participantToken);

        return new AttackResponse(round, exchange, participantToken, requiredSkillId);
    }

    public TargetResponse target(UUID gameId, int round, String participantToken, TargetRequest request) {
        Room room = resolveRoom(gameId);
        int seq = room.currentSessionSeq();
        String roomCode = room.roomCode();

        requireCurrentRound(roomCode, seq, round);
        int exchange = requireCurrentExchange(roomCode, seq);
        String attacker = ninjaRedis.getAttacker(roomCode, seq, round, exchange);
        if (attacker == null || !attacker.equals(participantToken)) {
            throw new BusinessException(ErrorCode.NINJA_NOT_ATTACKER);
        }
        String targetToken = request.targetToken();
        if (targetToken.equals(participantToken) || !ninjaRedis.isAlive(roomCode, seq, round, targetToken)) {
            throw new BusinessException(ErrorCode.NINJA_INVALID_TARGET);
        }
        if (!ninjaRedis.claimTarget(roomCode, seq, round, exchange, targetToken)) {
            throw new BusinessException(ErrorCode.NINJA_TARGET_ALREADY_SET);
        }
        if (!ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TARGET")) {
            // 타임아웃이 먼저 교환을 닫아버린 경합 — 데미지는 적용하지 않는다.
            throw new BusinessException(ErrorCode.NINJA_ROUND_CLOSED);
        }

        Skill skill = findExchangeSkill(roomCode, seq, round, exchange);
        int damage = skill.getDamage();
        long hpAfter = ninjaRedis.decrementHp(roomCode, seq, round, targetToken, damage);
        boolean eliminated = hpAfter <= 0;
        if (eliminated) {
            ninjaRedis.eliminate(roomCode, seq, round, targetToken, Instant.now());
        }
        int hpAfterClamped = (int) Math.max(hpAfter, 0);
        // 인터미션 스냅샷 재현용으로 결과를 attack 해시에 남긴다.
        ninjaRedis.recordAttackResolution(roomCode, seq, round, exchange, damage, hpAfterClamped, eliminated);

        int aliveCount = ninjaRedis.getAlivePlayers(roomCode, seq, round).size();
        boolean roundEnded = aliveCount <= 1 || exchange >= MAX_EXCHANGES_PER_ROUND;
        log.info("[Service] target : round={} ex={} attacker={} target={} skill={}({}) dmg={} hpAfter={} elim={} 생존={} roundEnded={}",
            round, exchange, participantToken, targetToken, skill.getName(), skill.getId(),
            damage, hpAfterClamped, eliminated, aliveCount, roundEnded);

        boolean ending = false;
        if (roundEnded) {
            // 판 종료: 이 판의 탈락 순서로 점수를 부여하고, 마지막 판이면 게임을 끝낸다.
            saveBoutScore(room, seq, round);
            ending = isLastRound(roomCode, seq, round);
        }

        Instant now = Instant.now();
        Instant effectUntil = now.plus(EFFECT_DURATION);
        // 게임 종료 결정타면 카운트다운 없이 이펙트만 재생하고 최종 순위로 — nextRoundAt은 null.
        Instant nextRoundAt = ending ? null : effectUntil.plus(COUNTDOWN_DURATION);

        ninjaRedis.enterIntermission(roomCode, seq, effectUntil, nextRoundAt);
        eventPublisher.publish(room.roomId(), "ninja:attack-resolved",
            new AttackResolvedPayload(round, exchange, participantToken, targetToken, skill.getId(), damage,
                hpAfterClamped, eliminated, NinjaPhase.INTERMISSION, effectUntil, nextRoundAt, roundEnded, ending));

        // 인터미션 종료 시점에 다음 진행을 서버가 연다(전원 동일 타이밍 → 선입력 방지).
        Runnable next = resolveNext(room, seq, round, exchange, roundEnded, ending);
        scheduleIntermissionTask(room, seq, ending ? effectUntil : nextRoundAt, next);

        return new TargetResponse(round, exchange, participantToken, targetToken, skill.getId(), damage,
            hpAfterClamped, eliminated, roundEnded, ending);
    }

    // 인터미션 이후 무엇을 할지: 게임 종료 / 다음 판 시작 / 같은 판의 다음 교환.
    private Runnable resolveNext(Room room, int seq, int round, int exchange, boolean roundEnded, boolean ending) {
        if (ending) {
            return () -> finishGame(room, seq);
        }
        if (roundEnded) {
            return () -> startRound(room, seq, round + 1);
        }
        return () -> startExchange(room, seq, round, exchange + 1);
    }

    private boolean isLastRound(String roomCode, int seq, int round) {
        Integer totalRounds = ninjaRedis.getTotalRounds(roomCode, seq);
        return totalRounds != null && round >= totalRounds;
    }

    private void scheduleIntermissionTask(Room room, int seq, Instant runAt, Runnable task) {
        cancelPendingTimeout(room.roomCode(), seq);
        ScheduledFuture<?> future = taskScheduler.schedule(task, runAt);
        pendingTimeouts.put(timerKey(room.roomCode(), seq), future);
    }

    // 판 시작: 전원 풀피로 되살리고(HP/생존/탈락순 리셋) 첫 교환을 연다.
    private void startRound(Room room, int seq, int round) {
        String roomCode = room.roomCode();
        Set<String> participants = ninjaRedis.getParticipants(roomCode, seq);
        if (participants == null || participants.size() < MIN_PLAYERS) {
            // 참가자 정보가 유실된 이상 상태 — 방어적으로 종료.
            finishGame(room, seq);
            return;
        }
        ninjaRedis.startRound(roomCode, seq, round, participants, INITIAL_HP);
        ninjaRedis.setCurrentRound(roomCode, seq, round);
        log.info("[Service] startRound : round={}/{} 시작 (전원 {}명 풀피 리셋)",
            round, ninjaRedis.getTotalRounds(roomCode, seq), participants.size());
        startExchange(room, seq, round, 1);
    }

    private void startExchange(Room room, int seq, int round, int exchange) {
        String roomCode = room.roomCode();
        Long skillId = ninjaRedis.drawNextSkill(roomCode, seq);
        if (skillId == null) {
            // 스킬 풀이 비어있는 등 이상 상태 — 방어적으로 게임을 종료시킨다.
            finishGame(room, seq);
            return;
        }
        Instant startedAt = Instant.now();
        ninjaRedis.openExchange(roomCode, seq, round, exchange, skillId, startedAt);
        ninjaRedis.setCurrentExchange(roomCode, seq, exchange);
        // 정상 진행 단계로 되돌리고 이전 인터미션의 이펙트/카운트다운 시각을 지운다.
        ninjaRedis.enterRound(roomCode, seq);

        Instant deadline = startedAt.plus(EXCHANGE_DURATION);
        log.info("[Service] startExchange : round={} ex={} skillId={} 제한시간={}초",
            round, exchange, skillId, EXCHANGE_DURATION.toSeconds());
        eventPublisher.publish(room.roomId(), "ninja:round-started", new RoundStartedPayload(round, exchange, deadline));
        ScheduledFuture<?> future =
            taskScheduler.schedule(() -> handleTimeout(room, seq, round, exchange), deadline);
        pendingTimeouts.put(timerKey(roomCode, seq), future);
    }

    private void handleTimeout(Room room, int seq, int round, int exchange) {
        String roomCode = room.roomCode();
        pendingTimeouts.remove(timerKey(roomCode, seq));
        if (!ninjaRedis.closeExchange(roomCode, seq, round, exchange, "TIMEOUT")) {
            return; // 이미 대상 지정으로 닫혔거나, 이전 세션의 좀비 타이머 — no-op.
        }
        log.info("[Service] handleTimeout : round={} ex={} 제한시간 내 아무도 콤보 완성 못함 — 공격 없이 교환 종료", round, exchange);

        // 타임아웃은 탈락이 없어 생존자 수가 그대로다. 상한에 도달했으면 판을 강제 마감, 아니면 다음 교환.
        boolean roundEnded = exchange >= MAX_EXCHANGES_PER_ROUND;
        boolean ending = false;
        if (roundEnded) {
            saveBoutScore(room, seq, round);
            ending = isLastRound(roomCode, seq, round);
        }
        if (ending) {
            eventPublisher.publish(room.roomId(), "ninja:round-timeout",
                new RoundTimeoutPayload(round, exchange, NinjaPhase.ENDED, null));
            finishGame(room, seq);
            return;
        }
        // 이펙트 없이 3초 카운트다운만 태우고 다음 진행으로.
        Instant nextRoundAt = Instant.now().plus(COUNTDOWN_DURATION);
        ninjaRedis.enterIntermission(roomCode, seq, null, nextRoundAt);
        eventPublisher.publish(room.roomId(), "ninja:round-timeout",
            new RoundTimeoutPayload(round, exchange, NinjaPhase.INTERMISSION, nextRoundAt));
        Runnable next = resolveNext(room, seq, round, exchange, roundEnded, false);
        scheduleIntermissionTask(room, seq, nextRoundAt, next);
    }

    // 이 판(round)의 결과를 순위로 환산해 점수를 저장한다 — 생존자는 HP 내림차순, 그다음 이 판의 탈락자를
    // 늦게 탈락한 순서로 이어붙인다. 최후 1인으로 끝난 정상 판이면 [생존자, 마지막탈락, ..., 첫탈락] 형태.
    private void saveBoutScore(Room room, int seq, int round) {
        List<UUID> participantIdsByRank = buildBoutRanking(room, seq, round).stream()
            .map(entry -> UUID.fromString(entry.token()))
            .toList();
        SaveRoundResult result = gameScoreService.saveRoundRanking(room.roomId(), seq, round, participantIdsByRank);
        if (result != SaveRoundResult.SUCCESS && result != SaveRoundResult.ALREADY_SAVED) {
            throw new IllegalStateException("Failed to save ninja round score: " + result);
        }
    }

    private void finishGame(Room room, int seq) {
        List<RankingEntry> ranking = buildFinalRanking(room, seq);
        ninjaRedis.saveRanking(room.roomCode(), seq, ranking.stream().map(RankingEntry::token).toList());
        ninjaRedis.enterEnded(room.roomCode(), seq);
        log.info("[Service] finishGame : roomCode={} seq={} 최종 순위(누적점수순)={}", room.roomCode(), seq, ranking);
        eventPublisher.publish(room.roomId(), "ninja:game-ended", new GameEndedPayload(ranking));
        // 이 게임이 끝났다는 사실만 알린다 — 코스의 다음 칸으로 넘길지 종합 결과로 갈지는
        // 코스 도메인의 판단이다(닌자는 자기가 코스의 몇 번째인지도 모른다).
        applicationEventPublisher.publishEvent(
            new GameSessionFinishedEvent(room.roomId(), seq)
        );
    }

    // 한 판 안에서의 순위(점수 부여용): 생존자 HP 내림차순 → 이 판 탈락자(늦게 탈락 순).
    private List<RankingEntry> buildBoutRanking(Room room, int seq, int round) {
        String roomCode = room.roomCode();
        Map<Object, Object> hp = ninjaRedis.getAllHp(roomCode, seq, round);
        List<String> aliveByHpDesc = new ArrayList<>(ninjaRedis.getAlivePlayers(roomCode, seq, round));
        aliveByHpDesc.sort(
            Comparator.<String>comparingInt(token -> parseInt(hp.get(token))).reversed()
                .thenComparing(token -> token)
        );
        List<String> eliminatedDesc = ninjaRedis.getEliminatedOrderDesc(roomCode, seq, round);

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

    // 게임 최종 순위: 판별로 누적된 세션 점수 내림차순. 동점은 토큰으로 결정적 정렬.
    private List<RankingEntry> buildFinalRanking(Room room, int seq) {
        Map<UUID, Long> totals = gameScoreService.getSessionTotals(room.roomId(), seq);
        List<String> participants = new ArrayList<>(ninjaRedis.getParticipants(room.roomCode(), seq));
        participants.sort(
            Comparator.<String>comparingLong(token -> totals.getOrDefault(UUID.fromString(token), 0L)).reversed()
                .thenComparing(token -> token)
        );
        return IntStream.range(0, participants.size())
            .mapToObj(i -> new RankingEntry(participants.get(i), i + 1))
            .toList();
    }

    private void requireCurrentRound(String roomCode, int seq, int round) {
        Integer currentRound = ninjaRedis.getCurrentRound(roomCode, seq);
        if (currentRound == null || round != currentRound) {
            throw new BusinessException(ErrorCode.NINJA_STALE_ROUND);
        }
    }

    private int requireCurrentExchange(String roomCode, int seq) {
        Integer exchange = ninjaRedis.getCurrentExchange(roomCode, seq);
        if (exchange == null) {
            throw new BusinessException(ErrorCode.NINJA_STALE_ROUND);
        }
        return exchange;
    }

    private Skill findExchangeSkill(String roomCode, int seq, int round, int exchange) {
        Long skillId = ninjaRedis.getExchangeSkillId(roomCode, seq, round, exchange);
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

    private static int parseInt(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}
