package com.camon.domain.game.charades.service;

import com.camon.domain.game.charades.dto.CharadesGuessRequest;
import com.camon.domain.game.charades.dto.CharadesGuessResponse;
import com.camon.domain.game.charades.dto.CharadesWordResponse;
import com.camon.domain.room.domain.Participant;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CharadesGameFacade {

    private final CharadesGameService charadesGameService;
    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;

    public CharadesGameFacade(
        CharadesGameService charadesGameService,
        RoomRepository roomRepository,
        ParticipantRepository participantRepository
    ) {
        this.charadesGameService = charadesGameService;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
    }

    public CharadesWordResponse getCurrentWord(
        Long gameId,
        UUID participantId
    ) {
        ParticipantContext context = resolveParticipant(participantId);
        return charadesGameService.getCurrentWord(
            context.room().roomId(),
            gameId,
            participantId
        );
    }

    public CharadesGuessResponse submitGuess(
        Long gameId,
        UUID participantId,
        CharadesGuessRequest request
    ) {
        ParticipantContext context = resolveParticipant(participantId);
        return charadesGameService.submitGuess(
            context.room().roomId(),
            gameId,
            context.participant(),
            request
        );
    }

    private ParticipantContext resolveParticipant(UUID participantId) {
        UUID roomId = participantRepository.findCurrentRoomId(participantId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_ACCESS_DENIED)
            );
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_NOT_FOUND)
            );
        Participant participant = participantRepository
            .findById(room.roomId(), participantId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.ROOM_ACCESS_DENIED)
            );
        return new ParticipantContext(room, participant);
    }

    private record ParticipantContext(
        Room room,
        Participant participant
    ) {
    }
}
