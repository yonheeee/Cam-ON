package com.camon.domain.game.common.repository;

import com.camon.domain.game.common.MissionTopic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionTopicRepository extends JpaRepository<MissionTopic, Long> {

    List<MissionTopic> findAllByGameGameIdAndIsActiveTrueOrderByNameAsc(Long gameId);

    Optional<MissionTopic> findByGameGameIdAndName(Long gameId, String name);

    boolean existsByTopicIdAndGameGameIdAndIsActiveTrue(Long topicId, Long gameId);
}
