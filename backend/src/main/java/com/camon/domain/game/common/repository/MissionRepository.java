package com.camon.domain.game.common.repository;

import com.camon.domain.game.common.Mission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    List<Mission> findAllByGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
        Long gameId,
        Long topicId,
        String missionType
    );

    List<Mission> findAllByTopicTopicIdAndMissionTypeAndIsActiveTrue(
        Long topicId,
        String missionType
    );

    Optional<Mission>
        findByMissionIdAndGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
            Long missionId,
            Long gameId,
            Long topicId,
            String missionType
        );
}
