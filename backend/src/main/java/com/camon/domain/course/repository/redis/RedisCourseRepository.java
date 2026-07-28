package com.camon.domain.course.repository.redis;

import com.camon.domain.course.domain.CourseItem;
import com.camon.domain.course.repository.CourseReplaceResult;
import com.camon.domain.course.repository.CourseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisCourseRepository implements CourseRepository {

    // 항목 하나가 차지하는 ARGV 칸 수 (game_id, round_count, topic_id)
    private static final int FIELDS_PER_ITEM = 3;
    // 항목 ARGV가 시작하는 위치 (ARGV[1]=prefix, ARGV[2]=length)
    private static final int ITEM_ARGV_OFFSET = 3;

    private static final long RESULT_SUCCESS = 1L;
    private static final long RESULT_ROOM_NOT_FOUND = 0L;
    private static final long RESULT_NOT_WAITING = -1L;

    // 코스 교체는 "옛 항목 삭제 + 새 항목 기록 + course_length 갱신"이 반드시 함께 일어나야 한다.
    // 중간에 끊기면 지워진 칸과 남은 칸이 섞여 코스 길이와 실제 항목 수가 어긋난다 → 한 스크립트로 묶는다.
    // status 가드도 여기 둔다(서비스에서 검사하고 다시 여기서 검사하는 이중 확인) — 방장이 시작
    // 버튼을 누르는 것과 코스를 저장하는 것이 동시에 일어나도 진행 중인 코스가 바뀌지 않게.
    private static final DefaultRedisScript<Long> REPLACE_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            if redis.call('HGET', KEYS[1], 'status') ~= 'WAITING' then
                return -1
            end

            local prefix = ARGV[1]
            local newLength = tonumber(ARGV[2])
            local oldLength = tonumber(redis.call('HGET', KEYS[1], 'course_length') or '0')
            for i = 1, oldLength do
                redis.call('DEL', prefix .. i)
            end

            for i = 1, newLength do
                local base = 3 + (i - 1) * 3
                local key = prefix .. i
                redis.call('HSET', key,
                    'game_id', ARGV[base],
                    'round_count', ARGV[base + 1])
                -- 주제를 쓰지 않는 게임은 빈 문자열로 넘어온다 → 필드 자체를 만들지 않는다.
                if ARGV[base + 2] ~= '' then
                    redis.call('HSET', key, 'topic_id', ARGV[base + 2])
                end
            end

            redis.call('HSET', KEYS[1], 'course_length', newLength)
            return 1
            """, Long.class);

    // course_length를 읽고 그만큼의 칸을 한 번에 훑어 [game_id, round_count, topic_id] 삼중항을
    // 평평하게 이어 반환한다. 여러 번 왕복하며 읽으면 저장이 끼어들어 잘린 코스를 볼 수 있다.
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> FIND_ALL_SCRIPT =
        new DefaultRedisScript<>("""
            local result = {}
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return result
            end
            local length = tonumber(redis.call('HGET', KEYS[1], 'course_length') or '0')
            local prefix = ARGV[1]
            for i = 1, length do
                local key = prefix .. i
                local gameId = redis.call('HGET', key, 'game_id')
                if gameId then
                    table.insert(result, gameId)
                    table.insert(result, redis.call('HGET', key, 'round_count') or '0')
                    table.insert(result, redis.call('HGET', key, 'topic_id') or '')
                end
            end
            return result
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCourseRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public CourseReplaceResult replace(
        UUID roomId,
        String roomCode,
        List<CourseItem> items
    ) {
        List<String> argv = new ArrayList<>(
            ITEM_ARGV_OFFSET - 1 + items.size() * FIELDS_PER_ITEM
        );
        argv.add(RedisCourseKeys.coursePrefix(roomCode));
        argv.add(Integer.toString(items.size()));
        for (CourseItem item : items) {
            argv.add(Long.toString(item.gameId()));
            argv.add(Integer.toString(item.roundCount()));
            argv.add(item.topicId() == null ? "" : Long.toString(item.topicId()));
        }

        Long result = redisTemplate.execute(
            REPLACE_SCRIPT,
            List.of(RedisCourseKeys.room(roomId)),
            argv.toArray()
        );
        if (result == null || result == RESULT_ROOM_NOT_FOUND) {
            return CourseReplaceResult.ROOM_NOT_FOUND;
        }
        if (result == RESULT_NOT_WAITING) {
            return CourseReplaceResult.ROOM_NOT_WAITING;
        }
        if (result == RESULT_SUCCESS) {
            return CourseReplaceResult.SUCCESS;
        }
        throw new IllegalStateException("Unexpected course replace result: " + result);
    }

    @Override
    public List<CourseItem> findAll(UUID roomId, String roomCode) {
        @SuppressWarnings("unchecked")
        List<Object> flat = redisTemplate.execute(
            FIND_ALL_SCRIPT,
            List.of(RedisCourseKeys.room(roomId)),
            RedisCourseKeys.coursePrefix(roomCode)
        );
        if (flat == null || flat.isEmpty()) {
            return List.of();
        }

        List<CourseItem> items = new ArrayList<>(flat.size() / FIELDS_PER_ITEM);
        for (int offset = 0; offset + FIELDS_PER_ITEM <= flat.size();
             offset += FIELDS_PER_ITEM) {
            String topicId = String.valueOf(flat.get(offset + 2));
            items.add(new CourseItem(
                items.size() + 1,
                Long.valueOf(String.valueOf(flat.get(offset))),
                Integer.parseInt(String.valueOf(flat.get(offset + 1))),
                topicId.isEmpty() ? null : Long.valueOf(topicId)
            ));
        }
        return items;
    }

    @Override
    public Optional<CourseItem> find(UUID roomId, String roomCode, int idx) {
        // 코스는 최대 몇 칸이라 전체를 읽고 고르는 편이 별도 스크립트를 두는 것보다 단순하다.
        return findAll(roomId, roomCode).stream()
            .filter(item -> item.idx() == idx)
            .findFirst();
    }
}
