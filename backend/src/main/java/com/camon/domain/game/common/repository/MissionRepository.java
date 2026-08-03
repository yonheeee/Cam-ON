package com.camon.domain.game.common.repository;

import com.camon.domain.game.common.Mission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    List<Mission> findAllByGameGameIdAndMissionTypeAndIsActiveTrue(
        Long gameId,
        String missionType
    );

    List<Mission> findAllByGameGameIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
        Long gameId,
        Long topicId,
        String missionType
    );

    List<Mission> findAllByGameGameIdAndTopicTopicIdAndMissionType(
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

    // gameId 없이 조회하는 버전. 턴 타임아웃은 TaskScheduler 콜백에서 터지는데 그 경로엔
    // gameId가 없다(방/세션/라운드만 넘어온다). 한 주제는 한 게임에만 속하므로 topicId가
    // 이미 게임을 특정한다 — 아래 countByTopicTopicIdAndIsActiveTrue와 같은 근거다.
    Optional<Mission> findByMissionIdAndTopicTopicIdAndMissionTypeAndIsActiveTrue(
        Long missionId,
        Long topicId,
        String missionType
    );

    // 주제별 제시어 수 — 코스 설정 화면이 "라운드 수 x 인원"이 제시어 수를 넘지 않는지
    // 미리 보여주는 데 쓴다. 한 주제는 한 게임에만 속하므로 mission_type까지 걸지 않는다.
    int countByTopicTopicIdAndIsActiveTrue(Long topicId);
}
