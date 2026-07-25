package com.camon.domain.game.ninja.service;

import com.camon.domain.game.ninja.dto.AttackRequest;
import com.camon.domain.game.ninja.dto.AttackResponse;
import com.camon.domain.game.ninja.dto.NinjaStateResponse;
import com.camon.domain.game.ninja.dto.RoundSkillResponse;
import com.camon.domain.game.ninja.dto.TargetRequest;
import com.camon.domain.game.ninja.dto.TargetResponse;
import com.camon.domain.game.ninja.repository.NinjaRedisRepository;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NinjaGameFacade {

    private final NinjaGameService ninjaGameService;
    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final NinjaRedisRepository ninjaRedisRepository;

    public NinjaGameFacade(
        NinjaGameService ninjaGameService,
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        NinjaRedisRepository ninjaRedisRepository
    ) {
        this.ninjaGameService = ninjaGameService;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.ninjaRedisRepository = ninjaRedisRepository;
    }

    public RoundSkillResponse getRoundSkill(
        Long gameId,
        int round,
        UUID participantId
    ) {
        GameContext context = resolve(gameId, participantId);
        return ninjaGameService.getRoundSkill(context.roomId(), round);
    }

    public NinjaStateResponse getState(Long gameId, UUID participantId) {
        GameContext context = resolve(gameId, participantId);
        return ninjaGameService.getState(context.roomId());
    }

    public AttackResponse attack(
        Long gameId,
        int round,
        UUID participantId,
        AttackRequest request
    ) {
        GameContext context = resolve(gameId, participantId);
        return ninjaGameService.attack(
            context.roomId(),
            round,
            participantId.toString(),
            request
        );
    }

    public TargetResponse target(
        Long gameId,
        int round,
        UUID participantId,
        TargetRequest request
    ) {
        GameContext context = resolve(gameId, participantId);
        return ninjaGameService.target(
            context.roomId(),
            round,
            participantId.toString(),
            request
        );
    }

    private GameContext resolve(Long gameId, UUID participantId) {
        UUID roomId = participantRepository.findCurrentRoomId(participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_ACCESS_DENIED));
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        participantRepository.findById(roomId, participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_ACCESS_DENIED));

        Long currentGameId = ninjaRedisRepository.getGameId(
            room.roomCode(),
            room.currentSessionSeq()
        );
        if (currentGameId == null) {
            throw new BusinessException(ErrorCode.NINJA_SESSION_NOT_FOUND);
        }
        if (!currentGameId.equals(gameId)) {
            throw new BusinessException(ErrorCode.GAME_NOT_CURRENT);
        }
        return new GameContext(room.roomId());
    }

    private record GameContext(UUID roomId) {
    }
}
