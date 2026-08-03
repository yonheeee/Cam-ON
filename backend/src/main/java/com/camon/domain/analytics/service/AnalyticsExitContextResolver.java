package com.camon.domain.analytics.service;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.repository.CourseRepository;
import com.camon.domain.game.charades.repository.CharadesRedisRepository;
import com.camon.domain.game.common.service.GameCatalogService;
import com.camon.domain.game.ninja.repository.NinjaRedisRepository;
import com.camon.domain.room.domain.Room;
import com.camon.domain.room.repository.RoomRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AnalyticsExitContextResolver {

    private final RoomRepository roomRepository;
    private final CourseRepository courseRepository;
    private final GameCatalogService gameCatalogService;
    private final NinjaRedisRepository ninjaRepository;
    private final CharadesRedisRepository charadesRepository;

    public AnalyticsExitContextResolver(
        RoomRepository roomRepository,
        CourseRepository courseRepository,
        GameCatalogService gameCatalogService,
        NinjaRedisRepository ninjaRepository,
        CharadesRedisRepository charadesRepository
    ) {
        this.roomRepository = roomRepository;
        this.courseRepository = courseRepository;
        this.gameCatalogService = gameCatalogService;
        this.ninjaRepository = ninjaRepository;
        this.charadesRepository = charadesRepository;
    }

    public Map<String, Object> resolve(UUID roomId) {
        try {
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return Map.of();
            }
            LinkedHashMap<String, Object> context = new LinkedHashMap<>();
            context.put("roomStatus", room.status().name());
            if (!"PLAYING".equals(room.status().name())) {
                return Map.copyOf(context);
            }

            int seq = room.currentSessionSeq();
            context.put("sessionSeq", seq);
            CourseItem item = courseRepository
                .find(roomId, room.roomCode(), seq)
                .orElse(null);
            if (item == null) {
                return Map.copyOf(context);
            }
            String gameType = gameCatalogService.findName(item.gameId());
            context.put("gameType", gameType);
            addGamePosition(context, gameType, room.roomCode(), seq);
            return Map.copyOf(context);
        } catch (RuntimeException exception) {
            log.warn(
                "[Analytics] failed to resolve exit context: roomId={}",
                roomId,
                exception
            );
            return Map.of();
        }
    }

    private void addGamePosition(
        Map<String, Object> context,
        String gameType,
        String roomCode,
        int seq
    ) {
        if ("NINJA".equals(gameType)) {
            putIfNotNull(context, "roundNumber", ninjaRepository.getCurrentRound(roomCode, seq));
            putIfNotNull(context, "exchangeNumber", ninjaRepository.getCurrentExchange(roomCode, seq));
            var phase = ninjaRepository.getPhase(roomCode, seq);
            putIfNotNull(context, "phase", phase == null ? null : phase.name());
            return;
        }
        if ("CHARADES".equals(gameType)) {
            charadesRepository.findState(roomCode, seq).ifPresent(state -> {
                context.put("roundNumber", state.currentRound());
                context.put("turnNumber", state.currentTurn());
                context.put("phase", state.status().name());
            });
        }
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
