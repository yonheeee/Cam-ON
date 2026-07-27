package com.camon.domain.game.common.service;

import com.camon.domain.game.common.dto.StartGameRequest;
import com.camon.domain.game.common.dto.StartGameResponse;
import com.camon.domain.game.ninja.service.NinjaGameService;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.domain.RoomStatus;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 방장이 대기방에서 게임을 시작할 때의 진입점. 방/참가자 상태(방장 여부, 전원 준비)를 검증하고
// 방 상태를 PLAYING으로 전환한 뒤, 해당 게임 세션을 연다.
//
// 이 검증·상태 전환이 없던 임시 구조(프론트가 dev seed 엔드포인트를 직접 호출)에서 발생하던
// 세 버그(전원 준비 전 시작 / 일부 참가자 미전환 / 게임 중 입장 미차단)를 여기서 함께 해결한다.
// - 전원 준비 검증 → 준비 안 된 참가자가 있으면 시작 거부
// - WAITING→PLAYING 전환 → join/ready-toggle Lua 스크립트의 status 가드가 자동으로 작동
//   (게임 중 입장·준비변경 차단은 이미 레포지토리에 있었고, status가 안 바뀌던 게 원인이었다)
// - 참가자 토큰을 클라이언트 스냅샷이 아니라 서버의 실제 참가자 목록에서 만든다 → 전원이 세션에 포함
//
// TEMP: 지금은 닌자만 구현돼 있어 gameId와 무관하게 NinjaGameService로 위임한다. course/session
// 도메인이 생기면 gameId(코스에 확정된 게임)별 시작 전략으로 분기하도록 확장하고, dev seed
// 컨트롤러는 제거한다.
@Slf4j
@Service
public class GameStartService {

    private static final int DEFAULT_TOTAL_ROUNDS = 5;

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final NinjaGameService ninjaGameService;

    public GameStartService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        NinjaGameService ninjaGameService
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.ninjaGameService = ninjaGameService;
    }

    public StartGameResponse start(
        UUID roomId,
        UUID requesterId,
        StartGameRequest request
    ) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw new BusinessException(ErrorCode.ROOM_ALREADY_STARTED);
        }

        List<Participant> participants = participantRepository.findAll(roomId);
        // 방장은 방 생성 시 ready=true로 시작하므로(게임 시작 버튼이 곧 준비 의사), 여기서는
        // 특별취급 없이 참가자 전원이 ready인지만 검사한다. (참가자 수 하한은 startSession의
        // 게임별 최소 인원 검증에 맡긴다.)
        if (participants.isEmpty()
            || participants.stream().anyMatch(participant -> !participant.ready())) {
            throw new BusinessException(ErrorCode.ROOM_NOT_ALL_READY);
        }

        Set<String> participantTokens = participants.stream()
            .map(participant -> participant.participantId().toString())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        int totalRounds = request.totalRounds() == null
            ? DEFAULT_TOTAL_ROUNDS
            : request.totalRounds();

        log.info("[Service] startGame : roomId={} gameId={} participants={} totalRounds={}",
            roomId, request.gameId(), participantTokens.size(), totalRounds);

        // status를 먼저 PLAYING으로 올려 game:started 브로드캐스트 시점엔 이미 PLAYING이 되도록 한다
        // (늦게 구독/재접속한 클라이언트가 방 status 조회로 게임 화면을 복구할 수 있게).
        roomRepository.updateStatus(roomId, RoomStatus.PLAYING);
        try {
            ninjaGameService.startSession(
                roomId,
                request.gameId(),
                participantTokens,
                totalRounds
            );
        } catch (RuntimeException e) {
            // 세션 오픈 실패 시 방을 다시 대기 상태로 되돌려 재시작이 가능하게 한다.
            roomRepository.updateStatus(roomId, RoomStatus.WAITING);
            throw e;
        }

        return new StartGameResponse(request.gameId(), totalRounds);
    }
}
