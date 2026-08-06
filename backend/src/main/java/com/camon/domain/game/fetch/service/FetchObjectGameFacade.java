package com.camon.domain.game.fetch.service;

import com.camon.domain.game.fetch.dto.FetchObjectStateResponse;
import com.camon.domain.game.fetch.dto.FetchSkipVoteResponse;
import com.camon.domain.game.fetch.dto.FetchSubmissionRequest;
import com.camon.domain.game.fetch.dto.FetchSubmissionResponse;
import com.camon.domain.game.fetch.repository.FetchObjectRedisRepository;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FetchObjectGameFacade {

    private final FetchObjectGameService fetchObjectGameService;
    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final FetchObjectRedisRepository fetchRedis;

    public FetchObjectGameFacade(
        FetchObjectGameService fetchObjectGameService,
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        FetchObjectRedisRepository fetchRedis
    ) {
        this.fetchObjectGameService = fetchObjectGameService;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.fetchRedis = fetchRedis;
    }

    public FetchSubmissionResponse submit(
        Long gameId,
        UUID participantId,
        FetchSubmissionRequest request
    ) {
        Room room = resolveRoom(participantId);
        requireCurrentGame(room, gameId);
        return fetchObjectGameService.submit(room, participantId, request);
    }

    public FetchObjectStateResponse getState(
        Long gameId,
        UUID participantId
    ) {
        Room room = resolveRoom(participantId);
        requireCurrentGame(room, gameId);
        return fetchObjectGameService.getState(room);
    }

    public FetchSkipVoteResponse voteSkip(
        Long gameId,
        UUID participantId
    ) {
        Room room = resolveRoom(participantId);
        requireCurrentGame(room, gameId);
        return fetchObjectGameService.voteSkip(room, participantId);
    }

    private void requireCurrentGame(Room room, Long gameId) {
        Long currentGameId = fetchRedis.getGameId(
            room.roomCode(),
            room.currentSessionSeq()
        );
        if (currentGameId == null) {
            throw new BusinessException(
                ErrorCode.FETCH_OBJECT_SESSION_NOT_FOUND
            );
        }
        if (!currentGameId.equals(gameId)) {
            throw new BusinessException(ErrorCode.GAME_NOT_CURRENT);
        }
    }

    private Room resolveRoom(UUID participantId) {
        UUID roomId = participantRepository.findCurrentRoomId(participantId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_ACCESS_DENIED)
            );
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_NOT_FOUND)
            );
        participantRepository.findById(roomId, participantId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_ACCESS_DENIED)
            );
        return room;
    }
}
