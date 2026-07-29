package com.camon.domain.course.service;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.dto.CourseItemRequest;
import com.camon.domain.course.dto.CourseItemResponse;
import com.camon.domain.course.dto.CourseResponse;
import com.camon.domain.course.dto.UpdateCourseRequest;
import com.camon.domain.course.repository.CourseReplaceResult;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.course.ws.CourseEventPublisher;
import com.camon.domain.game.common.Game;
import com.camon.domain.game.common.MissionTopic;
import com.camon.domain.game.common.repository.MissionTopicRepository;
import com.camon.domain.game.common.service.GameCatalogService;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.ParticipantRepository;
import com.camon.domain.room.repository.RoomRepository;
import com.camon.global.exception.BusinessException;
import com.camon.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 대기방에서 방장이 확정하는 "코스"(어떤 게임을 어떤 순서로 몇 라운드씩) 를 관리한다.
// 코스를 실제로 진행시키는 것(세션 열기/다음 게임으로 넘기기)은 CourseRunner의 책임이고,
// 여기는 확정 전까지의 편집·조회만 담당한다.
@Slf4j
@Service
public class CourseService {

    // 코스 길이 상한. 대기방 UI가 아이콘 칩으로 한 줄에 담을 수 있는 개수에 맞춘 값이고,
    // 게임 하나가 최대 10라운드까지 가므로 이보다 길면 한 방이 비현실적으로 길어진다.
    static final int MAX_COURSE_LENGTH = 7;

    private final RoomRepository roomRepository;
    private final ParticipantRepository participantRepository;
    private final CourseRepository courseRepository;
    private final GameCatalogService gameCatalogService;
    private final MissionTopicRepository missionTopicRepository;
    private final CourseEventPublisher courseEventPublisher;

    public CourseService(
        RoomRepository roomRepository,
        ParticipantRepository participantRepository,
        CourseRepository courseRepository,
        GameCatalogService gameCatalogService,
        MissionTopicRepository missionTopicRepository,
        CourseEventPublisher courseEventPublisher
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.courseRepository = courseRepository;
        this.gameCatalogService = gameCatalogService;
        this.missionTopicRepository = missionTopicRepository;
        this.courseEventPublisher = courseEventPublisher;
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourse(UUID roomId, UUID requesterId) {
        Room room = requireRoom(roomId);
        requireParticipant(roomId, requesterId);
        return toResponse(
            courseRepository.findAll(roomId, room.roomCode()),
            room.currentSessionSeq()
        );
    }

    @Transactional
    public CourseResponse updateCourse(
        UUID roomId,
        UUID requesterId,
        UpdateCourseRequest request
    ) {
        Room room = requireRoom(roomId);
        if (!room.hostParticipantId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.ROOM_NOT_HOST);
        }
        if (request.items().size() > MAX_COURSE_LENGTH) {
            throw new BusinessException(ErrorCode.COURSE_TOO_LONG);
        }

        List<CourseItem> items = new ArrayList<>(request.items().size());
        for (int index = 0; index < request.items().size(); index++) {
            items.add(validateItem(index + 1, request.items().get(index)));
        }

        CourseReplaceResult result =
            courseRepository.replace(roomId, room.roomCode(), items);
        if (result == CourseReplaceResult.ROOM_NOT_FOUND) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (result == CourseReplaceResult.ROOM_NOT_WAITING) {
            throw new BusinessException(ErrorCode.ROOM_ALREADY_STARTED);
        }
        log.info("[Service] updateCourse : roomCode={} 코스 {}칸 확정 {}",
            room.roomCode(), items.size(), items);

        // 상태를 바꾼 직후 같은 메서드에서 전파까지 완결시킨다(컨트롤러는 WS를 모른다).
        CourseResponse response = toResponse(items, room.currentSessionSeq());
        courseEventPublisher.publishCourseUpdated(roomId, response);
        return response;
    }

    // 코스에 담긴 게임들을 현재 인원으로 진행할 수 있는지 검증한다. 코스 저장 시점엔 사람이
    // 들락날락해서 검증할 수 없어(저장이 계속 실패한다) 게임 시작 시점으로 미뤄 둔 조건이다.
    @Transactional(readOnly = true)
    public void validatePlayable(List<CourseItem> items, int playerCount) {
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.COURSE_EMPTY);
        }
        for (CourseItem item : items) {
            Game game = gameCatalogService.requireSelectableGame(item.gameId());
            if (playerCount < game.getMinPlayers()
                || playerCount > game.getMaxPlayers()) {
                log.info("[Service] validatePlayable : '{}'은 {}~{}명인데 현재 {}명 — 시작 거부",
                    game.getName(), game.getMinPlayers(), game.getMaxPlayers(), playerCount);
                throw new BusinessException(ErrorCode.COURSE_PLAYERS_NOT_ELIGIBLE);
            }
            // min_rounds가 null인 게임(물건 가져오기)은 "참여자 수"가 최소 라운드다 —
            // 인원이 정해지는 이 시점에야 검증할 수 있다.
            if (game.getMinRounds() == null && item.roundCount() < playerCount) {
                throw new BusinessException(ErrorCode.COURSE_INVALID_ROUND_COUNT);
            }
        }
    }

    private CourseItem validateItem(int idx, CourseItemRequest request) {
        Game game = gameCatalogService.requireSelectableGame(request.gameId());
        // 서버가 세션을 열 수 없는 게임(아직 구현 안 된 물건 가져오기)은 코스에 담지 못하게 여기서
        // 막는다 — 담을 수 있게 두면 코스 중간에 도달했을 때 진행이 멈춘다.
        if (!gameCatalogService.isSupported(game.getName())) {
            throw new BusinessException(ErrorCode.COURSE_GAME_NOT_SUPPORTED);
        }
        validateRoundCount(game, request.roundCount());
        Long topicId = validateTopic(game, request.topicId());
        return new CourseItem(idx, game.getGameId(), request.roundCount(), topicId);
    }

    private static void validateRoundCount(Game game, int roundCount) {
        // min_rounds가 null이면 하한이 "참여자 수"라 지금은 확정할 수 없다 → 상한만 본다.
        int minRounds = game.getMinRounds() == null ? 1 : game.getMinRounds();
        int maxRounds = game.getMaxRounds() == null ? Integer.MAX_VALUE : game.getMaxRounds();
        if (roundCount < minRounds || roundCount > maxRounds) {
            throw new BusinessException(ErrorCode.COURSE_INVALID_ROUND_COUNT);
        }
    }

    private Long validateTopic(Game game, Long topicId) {
        if (!gameCatalogService.hasTopics(game.getGameId())) {
            // 주제를 쓰지 않는 게임에 주제가 붙어 오면 클라이언트 버그이므로 조용히 무시하지 않는다.
            if (topicId != null) {
                throw new BusinessException(ErrorCode.COURSE_TOPIC_NOT_ALLOWED);
            }
            return null;
        }
        if (topicId == null) {
            throw new BusinessException(ErrorCode.COURSE_TOPIC_REQUIRED);
        }
        if (!missionTopicRepository.existsByTopicIdAndGameGameIdAndIsActiveTrue(
            topicId,
            game.getGameId()
        )) {
            throw new BusinessException(ErrorCode.CHARADES_TOPIC_NOT_FOUND);
        }
        return topicId;
    }

    private CourseResponse toResponse(List<CourseItem> items, int currentSessionSeq) {
        return new CourseResponse(
            items.stream().map(this::toItemResponse).toList(),
            currentSessionSeq
        );
    }

    private CourseItemResponse toItemResponse(CourseItem item) {
        // 이름은 표시용이라, 사이에 게임/주제가 비활성화되더라도 코스 조회 자체는 실패시키지 않는다.
        String gameName = gameCatalogService.findName(item.gameId());
        String topicName = item.topicId() == null
            ? null
            : missionTopicRepository.findById(item.topicId())
                .map(MissionTopic::getName)
                .orElse(null);
        return new CourseItemResponse(
            item.idx(),
            item.gameId(),
            gameName,
            item.roundCount(),
            item.topicId(),
            topicName
        );
    }

    private Room requireRoom(UUID roomId) {
        return roomRepository.findById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    }

    private void requireParticipant(UUID roomId, UUID participantId) {
        participantRepository.findById(roomId, participantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_ACCESS_DENIED));
    }
}
